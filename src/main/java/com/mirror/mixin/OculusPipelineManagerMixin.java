package com.mirror.mixin;

import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPassContext;
import com.mirror.client.MirrorPipelineAccess;
import com.mirror.client.MirrorPipelineSlotKey;
import com.mirror.client.MirrorPipelineState;
import com.mirror.client.MirrorPipelineUnavailableException;
import com.mirror.client.MirrorTemporalStateAccess;
import com.mirror.client.OculusCompat;
import com.mirror.config.MirrorConfig;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns one stable Oculus pipeline per real dimension and mirror pipeline slot.
 *
 * <p>A slot is isolated by recursion depth and capture resolution. The slot remains alive for the
 * current Oculus shader generation even when all views disappear. A newly constructed pipeline is
 * WARMING until Embeddium's {@code IrisChunkProgramOverrides.createShaders()} completes; only then
 * is the slot READY. At most one WARMING/constructing slot may consume heavyweight shader work in a
 * real outer frame.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
abstract class OculusPipelineManagerMixin implements MirrorPipelineAccess {
    @Shadow
    private WorldRenderingPipeline pipeline;

    @Unique
    private final Map<MirrorPipelineSlotKey, MirrorPipelineState> mirror$mirrorPipelines = new HashMap<>();

    /**
     * Constructor-warmed pipelines created while Oculus is already loading its primary pipeline.
     * They are resolution-agnostic until first claimed; Iris resizes its RenderTargets on the first
     * beginLevelRendering call. Keeping two spares covers the normal direct + recursive buckets
     * without compiling a complete Iris pipeline in a live mirror frame.
     */
    @Unique
    private final Map<NamespacedId, Deque<MirrorPipelineState>> mirror$prewarmedPipelines = new HashMap<>();

    @Unique
    private final Set<NamespacedId> mirror$prewarmInitializedDimensions = new HashSet<>();

    @Unique
    private final Map<WorldRenderingPipeline, NamespacedId> mirror$primaryPipelineDimensions =
            new IdentityHashMap<>();

    @Unique
    private final Set<MirrorPipelineSlotKey> mirror$failedMirrorSlots = new HashSet<>();

    @Unique
    private boolean mirror$shaderGenerationFailed;

    @Unique
    private long mirror$outerFrameSequence;

    @Unique
    private int mirror$heavyBuildBudget = 1;

    @Inject(method = "preparePipeline", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$preparePipeline(NamespacedId realDimension,
                                        CallbackInfoReturnable<WorldRenderingPipeline> callback) {
        if (!OculusCompat.isMirrorPass() || !OculusCompat.isShaderPackInUse()) return;

        MirrorPassContext context = MirrorPassContext.current();
        WorldRenderingPipeline selected = mirror$prepareSlotPipeline(
                realDimension, context.pipelineSlot(), "view " + context.viewId());
        pipeline = selected;
        callback.setReturnValue(selected);
    }

    @Inject(method = "preparePipeline", at = @At("RETURN"), require = 1, remap = false)
    private void mirror$prewarmMirrorPipelines(NamespacedId realDimension,
                                                CallbackInfoReturnable<WorldRenderingPipeline> callback) {
        if (OculusCompat.isMirrorPass()) return;
        if (!(callback.getReturnValue() instanceof IrisRenderingPipeline primaryPipeline)) return;
        mirror$primaryPipelineDimensions.put(primaryPipeline, realDimension);
        if (mirror$shaderGenerationFailed || !mirror$prewarmInitializedDimensions.add(realDimension)) return;

        int desired = Math.min(2, Math.max(1, MirrorConfig.CLIENT.maxRecursionDepth.get()));
        Deque<MirrorPipelineState> warmed = mirror$prewarmedPipelines.computeIfAbsent(
                realDimension, ignored -> new ArrayDeque<>());
        if (warmed.size() >= desired) return;

        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) return;
        ProgramSet programs = pack.getProgramSet(realDimension);

        while (warmed.size() < desired) {
            long started = System.nanoTime();
            try {
                WorldRenderingPipeline prewarmed = new IrisRenderingPipeline(programs);
                if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
                    WorldRenderingSettings.INSTANCE.clearReloadRequired();
                }
                warmed.addLast(new MirrorPipelineState(prewarmed, started, Long.MIN_VALUE));
                MirrorDiagnostics.pipelinePrewarmed(realDimension, System.nanoTime() - started,
                        warmed.size(), desired);
            } catch (RuntimeException failure) {
                if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
                    WorldRenderingSettings.INSTANCE.clearReloadRequired();
                }
                // Prewarming is an optimization only. Do not turn an otherwise valid primary
                // Oculus pipeline into a startup failure; the normal mirror path retains P7's
                // contained failure handling if this shader cannot support secondary pipelines.
                Iris.logger.warn("Could not prewarm secondary mirror pipeline {} of {} for dimension {}; "
                                + "the mirror will fall back to lazy construction",
                        warmed.size() + 1, desired, realDimension, failure);
                break;
            }
        }
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$destroyWithOculus(CallbackInfo callback) {
        // PipelineManager.destroyPipeline is the sole shader-generation owner. View/resource cache
        // eviction never reaches this path, so terrain programs are not invalidated redundantly.
        mirror$destroyMirrorPipelines();
    }

    @Override
    public WorldRenderingPipeline mirror$getPipeline() {
        return pipeline;
    }

    @Override
    public void mirror$setPipeline(WorldRenderingPipeline value) {
        pipeline = value;
    }

    @Override
    public void mirror$beginFrame() {
        mirror$outerFrameSequence++;
        mirror$heavyBuildBudget = 1;
    }

    @Override
    public void mirror$markTerrainProgramsReady(WorldRenderingPipeline readyPipeline, long compileNanos) {
        for (Map.Entry<MirrorPipelineSlotKey, MirrorPipelineState> entry : mirror$mirrorPipelines.entrySet()) {
            MirrorPipelineState state = entry.getValue();
            if (state.pipeline() != readyPipeline || state.terrainReady()) continue;
            state.markTerrainReady();
            long totalWarmupNanos = System.nanoTime() - state.constructionStartedNanos();
            MirrorDiagnostics.terrainProgramsReady(
                    entry.getKey().dimension(), entry.getKey().slot(), compileNanos, totalWarmupNanos);
            return;
        }

        for (Map.Entry<NamespacedId, Deque<MirrorPipelineState>> entry : mirror$prewarmedPipelines.entrySet()) {
            for (MirrorPipelineState state : entry.getValue()) {
                if (state.pipeline() != readyPipeline || state.terrainReady()) continue;
                state.markTerrainReady();
                MirrorDiagnostics.terrainProgramsPrewarmed(
                        entry.getKey(), compileNanos, System.nanoTime() - state.constructionStartedNanos());
                return;
            }
        }
    }

    @Override
    public List<WorldRenderingPipeline> mirror$getPrewarmedPipelinesNeedingTerrain(
            WorldRenderingPipeline primaryPipeline) {
        NamespacedId dimension = mirror$primaryPipelineDimensions.get(primaryPipeline);
        if (dimension == null) return List.of();
        Deque<MirrorPipelineState> states = mirror$prewarmedPipelines.get(dimension);
        if (states == null || states.isEmpty()) return List.of();

        List<WorldRenderingPipeline> result = new ArrayList<>();
        for (MirrorPipelineState state : states) {
            if (!state.terrainReady()) result.add(state.pipeline());
        }
        return result;
    }

    @Unique
    private WorldRenderingPipeline mirror$prepareSlotPipeline(
            NamespacedId dimension, MirrorPassContext.PipelineSlot slot, String owner) {
        MirrorPipelineSlotKey key = new MirrorPipelineSlotKey(dimension, slot);
        if (mirror$shaderGenerationFailed) {
            throw new MirrorPipelineUnavailableException(
                    "Oculus shader generation is unavailable for secondary mirror pipelines", null);
        }
        MirrorPipelineState existing = mirror$mirrorPipelines.get(key);
        if (existing != null) {
            return existing.pipeline();
        }

        Deque<MirrorPipelineState> warmed = mirror$prewarmedPipelines.get(dimension);
        if (warmed != null && !warmed.isEmpty()) {
            MirrorPipelineState claimed = warmed.removeFirst();
            if (warmed.isEmpty()) mirror$prewarmedPipelines.remove(dimension);
            mirror$mirrorPipelines.put(key, claimed);
            Iris.logger.info("Claiming prewarmed mirror pipeline for dimension {}, resolution {} ({})",
                    dimension, key.resolution(), owner);
            return claimed.pipeline();
        }

        if (mirror$failedMirrorSlots.contains(key)) {
            throw new MirrorPipelineUnavailableException(
                    "Oculus previously failed to create the mirror shader pipeline for " + key, null);
        }
        if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
            throw new MirrorPipelineUnavailableException(
                    "Oculus world settings require reload before mirror pipeline creation", null);
        }
        if (mirror$heavyBuildBudget <= 0) {
            MirrorDiagnostics.recordDeferredPipelineBuild();
            throw new MirrorPipelineUnavailableException(
                    "Mirror pipeline construction deferred by the per-frame heavyweight build budget for " + key,
                    null);
        }

        mirror$heavyBuildBudget = 0;
        Iris.logger.info("Creating mirror pipeline for dimension {}, slot {} ({})", dimension, slot, owner);
        long constructionStart = System.nanoTime();
        WorldRenderingPipeline selected;
        try {
            // Do not call PipelineManager's Iris::createPipeline factory here. That wrapper is
            // intentionally global: on a constructor failure it records a user-facing load error,
            // marks Iris as fallback and returns Vanilla. Those side effects are correct for the
            // real world pipeline but catastrophic for an optional secondary mirror pipeline.
            ShaderPack pack = Iris.getCurrentPack().orElseThrow(() ->
                    new IllegalStateException("Oculus has no active shader pack for mirror pipeline construction"));
            ProgramSet programs = pack.getProgramSet(dimension);
            selected = new IrisRenderingPipeline(programs);
        } catch (RuntimeException error) {
            boolean shaderFailure = mirror$isShaderConstructionFailure(error);
            if (shaderFailure) mirror$shaderGenerationFailed = true;
            else mirror$failedMirrorSlots.add(key);
            if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
                WorldRenderingSettings.INSTANCE.clearReloadRequired();
            }
            if (shaderFailure) {
                Iris.logger.error("Mirror shader pipeline creation failed for dimension {} and slot {}. " +
                                "The current shader generation is quarantined for all secondary mirror pipelines.",
                        dimension, slot, error);
            } else {
                Iris.logger.error("Mirror shader pipeline creation failed for dimension {} and slot {}. " +
                                "This slot is quarantined until the next shader reload.",
                        dimension, slot, error);
            }
            throw new MirrorPipelineUnavailableException(
                    "Oculus could not create a secondary mirror shader pipeline for " + key, error);
        }

        // A secondary pipeline for the same real dimension must not request a global material-map
        // rebuild; the world/chunk material mapping itself did not change.
        if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
            WorldRenderingSettings.INSTANCE.clearReloadRequired();
        }

        long constructedAt = System.nanoTime();
        MirrorPipelineState state = new MirrorPipelineState(
                selected, constructionStart, mirror$outerFrameSequence);
        mirror$mirrorPipelines.put(key, state);
        MirrorDiagnostics.pipelineConstructed(dimension, slot, constructedAt - constructionStart);
        return selected;
    }

    @Override
    public void mirror$quarantineShaderGeneration(WorldRenderingPipeline failedPipeline, Throwable failure) {
        if (mirror$shaderGenerationFailed) return;

        MirrorPipelineSlotKey failedKey = null;
        for (Map.Entry<MirrorPipelineSlotKey, MirrorPipelineState> entry : mirror$mirrorPipelines.entrySet()) {
            if (entry.getValue().pipeline() == failedPipeline) {
                failedKey = entry.getKey();
                break;
            }
        }

        // A terrain shader compile/link failure is shader-source/generation scoped rather than a
        // mirror geometry failure. Disable all secondary pipelines for this generation, but keep the
        // primary Oculus pipeline alive. Resources are destroyed later by PipelineManager's normal
        // destroyPipeline lifecycle, never while the failed mirror pipeline is active on the stack.
        mirror$shaderGenerationFailed = true;
        MirrorDiagnostics.recordLateShaderQuarantine();
        if (failedKey == null) {
            Iris.logger.error("Late mirror terrain shader failure. The current Oculus shader generation "
                    + "is quarantined for secondary mirror pipelines.", failure);
        } else {
            Iris.logger.error("Late mirror terrain shader failure for dimension {} and slot {}. The current "
                            + "Oculus shader generation is quarantined for secondary mirror pipelines.",
                    failedKey.dimension(), failedKey.slot(), failure);
        }
    }

    @Override
    public void mirror$beginTerrainProgramBuild(WorldRenderingPipeline terrainPipeline) {
        for (Map.Entry<MirrorPipelineSlotKey, MirrorPipelineState> entry : mirror$mirrorPipelines.entrySet()) {
            MirrorPipelineState state = entry.getValue();
            if (state.pipeline() != terrainPipeline || state.terrainReady()) continue;

            // Construction and terrain creation for the same slot may complete in one outer frame.
            // If this WARMING slot survived from an earlier frame, claim the current frame only when
            // terrain creation is actually about to happen. This avoids starving other slots when a
            // mirror currently sees only sky/entities and never asks Embeddium for terrain shaders.
            if (state.heavyWorkFrame() == mirror$outerFrameSequence) return;
            if (mirror$heavyBuildBudget <= 0) {
                MirrorDiagnostics.recordDeferredPipelineBuild();
                throw new MirrorPipelineUnavailableException(
                        "Mirror terrain-program warm-up deferred by the per-frame heavyweight build budget for "
                                + entry.getKey(), null);
            }
            mirror$heavyBuildBudget = 0;
            state.setHeavyWorkFrame(mirror$outerFrameSequence);
            return;
        }
    }

    @Override
    public void mirror$releaseMirrorView(long viewId) {
        // Full Oculus pipelines are slot-owned, not view-owned. Only invalidate temporal ownership
        // for the released camera so a later reuse of the same stable view id cannot consume stale
        // colortex/custom-image contents retained by the long-lived slot.
        for (MirrorPipelineState state : mirror$mirrorPipelines.values()) {
            if (state.pipeline() instanceof MirrorTemporalStateAccess temporalState) {
                temporalState.mirror$releaseView(viewId);
            }
        }
    }

    @Unique
    private void mirror$destroyMirrorPipelines() {
        for (MirrorPipelineState state : mirror$mirrorPipelines.values()) {
            if (state.pipeline() == pipeline) {
                throw new IllegalStateException("Cannot destroy the active mirror pipeline");
            }
        }

        mirror$mirrorPipelines.forEach((key, state) -> {
            Iris.logger.info("Destroying mirror pipeline for dimension {} and resolution {}",
                    key.dimension(), key.resolution());
            state.pipeline().destroy();
        });
        mirror$prewarmedPipelines.forEach((dimension, states) -> {
            for (MirrorPipelineState state : states) {
                Iris.logger.info("Destroying unused prewarmed mirror pipeline for dimension {}", dimension);
                state.pipeline().destroy();
            }
        });
        mirror$mirrorPipelines.clear();
        mirror$prewarmedPipelines.clear();
        mirror$prewarmInitializedDimensions.clear();
        mirror$primaryPipelineDimensions.clear();
        mirror$failedMirrorSlots.clear();
        mirror$shaderGenerationFailed = false;
        mirror$heavyBuildBudget = 1;
    }

    @Unique
    private static boolean mirror$isShaderConstructionFailure(Throwable error) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            String className = cursor.getClass().getName();
            if (className.equals("net.minecraft.server.ChainedJsonException")
                    || className.equals("net.irisshaders.iris.gl.shader.ShaderCompileException")
                    || className.equals("net.irisshaders.iris.helpers.FakeChainedJsonException")
                    || className.equals("org.spongepowered.asm.mixin.injection.callback.CancellationException")) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null && (message.contains("Couldn't compile")
                    || message.contains("Could not compile")
                    || message.contains("Invalid shaders/core/")
                    || message.contains("compileShaderInternal is not cancellable"))) {
                return true;
            }
        }
        return false;
    }

}
