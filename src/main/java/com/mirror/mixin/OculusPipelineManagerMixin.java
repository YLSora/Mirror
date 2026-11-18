package com.mirror.mixin;

import com.mirror.client.MirrorCapturePool;
import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPassContext;
import com.mirror.client.MirrorPipelineAccess;
import com.mirror.client.MirrorPipelineSlotKey;
import com.mirror.client.MirrorPipelineState;
import com.mirror.client.MirrorPipelineUnavailableException;
import com.mirror.client.MirrorTemporalStateAccess;
import com.mirror.client.OculusCompat;
import com.mirror.client.OculusTerrainProgramCacheAccess;
import com.mirror.config.MirrorConfig;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
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
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns demand-driven secondary Oculus pipelines for the current shader generation.
 *
 * <p>Mirror slots are grouped by the semantic ProgramSet, not by the real dimension. Iris creates
 * separate ProgramSet objects for dimension overrides even when they resolve to the same world0
 * directory, so equivalent sets are canonicalized by their parsed shader sources.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
abstract class OculusPipelineManagerMixin implements MirrorPipelineAccess {
    @Shadow
    private WorldRenderingPipeline pipeline;

    @Unique
    private final Map<MirrorPipelineSlotKey, MirrorPipelineState> mirror$mirrorPipelines = new HashMap<>();

    @Unique
    private final Map<ProgramSet, Deque<MirrorPassContext.PipelineSlot>> mirror$prewarmQueues =
            new IdentityHashMap<>();

    @Unique
    private final Set<ProgramSet> mirror$prewarmInitialized =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private final List<ProgramSet> mirror$programSetGroups = new ArrayList<>();

    @Unique
    private final Map<NamespacedId, ProgramSet> mirror$programSetsByDimension = new HashMap<>();

    @Unique
    private final Map<ProgramSet, List<String>> mirror$programSetSignatures = new IdentityHashMap<>();

    @Unique
    private final Set<MirrorPipelineSlotKey> mirror$failedMirrorSlots = new HashSet<>();

    @Unique
    private final Set<ProgramSet> mirror$shaderGenerationFailed =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private long mirror$shaderGeneration;

    @Unique
    private long mirror$outerFrameSequence;

    @Unique
    private int mirror$heavyBuildBudget = 1;

    @Unique
    private boolean mirror$frameHadMirrorDemand;

    @Unique
    private ProgramSet mirror$frameDemandProgramSet;

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

    @Inject(method = "destroyPipeline", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$destroyWithOculus(CallbackInfo callback) {
        boolean shaderReload = Iris.getCurrentPack().isEmpty() || !OculusCompat.isShaderPackInUse();
        if (!shaderReload) {
            Iris.logger.info("Keeping {} mirror pipeline slots for shader generation {} across dimension pipeline switch",
                    mirror$mirrorPipelines.size(), mirror$shaderGeneration);
            return;
        }

        Iris.logger.info("Destroying mirror pipeline generation {} for shader reload/pack change", mirror$shaderGeneration);
        OculusTerrainProgramCacheAccess.clearMirrorPrograms();
        mirror$destroyMirrorPipelines("shader reload");
        mirror$shaderGeneration++;
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
        mirror$frameHadMirrorDemand = false;
        mirror$frameDemandProgramSet = null;
    }

    @Override
    public void mirror$finishFrame() {
        if (!mirror$frameHadMirrorDemand) return;
        ProgramSet programs = mirror$frameDemandProgramSet;
        mirror$frameHadMirrorDemand = false;
        mirror$frameDemandProgramSet = null;
        if (!MirrorConfig.CLIENT.prewarmMirrorPipelines.get() || programs == null
                || mirror$shaderGenerationFailed.contains(programs) || mirror$heavyBuildBudget <= 0) return;

        Deque<MirrorPassContext.PipelineSlot> queue = mirror$prewarmQueues.get(programs);
        if (queue == null || queue.isEmpty()) return;
        MirrorPassContext.PipelineSlot slot;
        MirrorPipelineSlotKey key;
        do {
            slot = queue.removeFirst();
            key = new MirrorPipelineSlotKey(programs, slot);
        } while (!queue.isEmpty()
                && (mirror$mirrorPipelines.containsKey(key) || mirror$failedMirrorSlots.contains(key)));
        if (mirror$mirrorPipelines.containsKey(key) || mirror$failedMirrorSlots.contains(key)) return;

        mirror$heavyBuildBudget = 0;
        try {
            long started = System.nanoTime();
            WorldRenderingPipeline spare = mirror$constructPipeline(programs, slot,
                    "lazy spare prewarm", false);
            mirror$mirrorPipelines.put(key, new MirrorPipelineState(spare, started, Long.MIN_VALUE));
            Iris.logger.info("Prewarmed mirror pipeline slot {} for shader generation {} after mirror demand",
                    key.slot(), mirror$shaderGeneration);
            MirrorDiagnostics.pipelinePrewarmed(key.label(), System.nanoTime() - started,
                    mirror$mirrorPipelines.size(), mirror$desiredSlotCount());
        } catch (RuntimeException failure) {
            if (mirror$isShaderConstructionFailure(failure)) mirror$shaderGenerationFailed.add(programs);
            else mirror$failedMirrorSlots.add(key);
            if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
                WorldRenderingSettings.INSTANCE.clearReloadRequired();
            }
            Iris.logger.warn("Could not prewarm mirror pipeline slot {} for shader generation {}; "
                            + "the slot remains lazy", slot, mirror$shaderGeneration, failure);
        }
    }

    @Override
    public void mirror$markTerrainProgramsReady(WorldRenderingPipeline readyPipeline, long compileNanos) {
        for (Map.Entry<MirrorPipelineSlotKey, MirrorPipelineState> entry : mirror$mirrorPipelines.entrySet()) {
            MirrorPipelineState state = entry.getValue();
            if (state.pipeline() != readyPipeline || state.terrainReady()) continue;
            state.markTerrainReady();
            long totalWarmupNanos = System.nanoTime() - state.constructionStartedNanos();
            MirrorDiagnostics.terrainProgramsReady(
                    entry.getKey().label(), entry.getKey().slot(), compileNanos, totalWarmupNanos);
            return;
        }
    }

    @Unique
    private WorldRenderingPipeline mirror$prepareSlotPipeline(
            NamespacedId dimension, MirrorPassContext.PipelineSlot slot, String owner) {
        ProgramSet programs = mirror$programSetFor(dimension);
        MirrorPipelineSlotKey key = new MirrorPipelineSlotKey(programs, slot);
        mirror$frameHadMirrorDemand = true;
        mirror$frameDemandProgramSet = programs;

        if (mirror$shaderGenerationFailed.contains(programs)) {
            throw new MirrorPipelineUnavailableException(
                    "Oculus shader generation is unavailable for secondary mirror pipelines", null);
        }
        MirrorPipelineState existing = mirror$mirrorPipelines.get(key);
        if (existing != null) return existing.pipeline();
        mirror$prewarmQueues.computeIfAbsent(programs, ignored -> new ArrayDeque<>()).remove(slot);

        if (mirror$failedMirrorSlots.contains(key)) {
            throw new MirrorPipelineUnavailableException(
                    "Oculus previously failed to create the mirror shader pipeline for " + key.label(), null);
        }
        if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
            throw new MirrorPipelineUnavailableException(
                    "Oculus world settings require reload before mirror pipeline creation", null);
        }
        if (mirror$heavyBuildBudget <= 0) {
            MirrorDiagnostics.recordDeferredPipelineBuild();
            throw new MirrorPipelineUnavailableException(
                    "Mirror pipeline construction deferred by the per-frame heavyweight build budget for " + key.slot(),
                    null);
        }

        mirror$heavyBuildBudget = 0;
        Iris.logger.info("Creating mirror pipeline for shader generation {}, program set {}, dimension {}, slot {} ({})",
                mirror$shaderGeneration, key.label(), dimension, slot, owner);
        long constructionStart = System.nanoTime();
        WorldRenderingPipeline selected;
        try {
            selected = mirror$constructPipeline(programs, slot, owner, true);
        } catch (RuntimeException error) {
            boolean shaderFailure = mirror$isShaderConstructionFailure(error);
            if (shaderFailure) mirror$shaderGenerationFailed.add(programs);
            else mirror$failedMirrorSlots.add(key);
            if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
                WorldRenderingSettings.INSTANCE.clearReloadRequired();
            }
            if (shaderFailure) {
                Iris.logger.error("Mirror shader pipeline creation failed for program set {} and slot {}. "
                                + "The current shader generation is quarantined for all secondary mirror pipelines.",
                        key.label(), slot, error);
            } else {
                Iris.logger.error("Mirror shader pipeline creation failed for program set {} and slot {}. "
                                + "This slot is quarantined until the next shader reload.", key.label(), slot, error);
            }
            throw new MirrorPipelineUnavailableException(
                    "Oculus could not create a secondary mirror shader pipeline for " + key.label(), error);
        }

        MirrorPipelineState state = new MirrorPipelineState(
                selected, constructionStart, mirror$outerFrameSequence);
        mirror$mirrorPipelines.put(key, state);
        mirror$triggerPrewarm(programs, slot);
        MirrorDiagnostics.pipelineConstructed(key.label(), slot, System.nanoTime() - constructionStart);
        return selected;
    }

    @Unique
    private WorldRenderingPipeline mirror$constructPipeline(ProgramSet programs,
                                                              MirrorPassContext.PipelineSlot slot,
                                                              String owner,
                                                              boolean required) {
        ShaderPack pack = Iris.getCurrentPack().orElseThrow(() ->
                new IllegalStateException("Oculus has no active shader pack for mirror pipeline construction"));
        WorldRenderingPipeline selected = new IrisRenderingPipeline(programs);
        if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
            WorldRenderingSettings.INSTANCE.clearReloadRequired();
        }
        return selected;
    }

    @Unique
    private void mirror$triggerPrewarm(ProgramSet programs, MirrorPassContext.PipelineSlot currentSlot) {
        if (!MirrorConfig.CLIENT.prewarmMirrorPipelines.get()
                || !mirror$prewarmInitialized.add(programs)) return;

        Deque<MirrorPassContext.PipelineSlot> queue = new ArrayDeque<>();
        int maxDepth = Math.max(1, MirrorConfig.CLIENT.maxRecursionDepth.get());
        for (int depth = 0; depth < maxDepth; depth++) {
            for (MirrorPassContext.ResolutionBucket bucket : MirrorCapturePool.shaderBuckets()) {
                MirrorPassContext.PipelineSlot slot = new MirrorPassContext.PipelineSlot(depth, bucket);
                if (!slot.equals(currentSlot)) queue.addLast(slot);
            }
        }
        mirror$prewarmQueues.put(programs, queue);
        Iris.logger.info("Mirror pipeline prewarm triggered by first mirror demand for program set {} "
                        + "in shader generation {}; {} spare slots queued",
                new MirrorPipelineSlotKey(programs, currentSlot).label(), mirror$shaderGeneration, queue.size());
    }

    @Unique
    private int mirror$desiredSlotCount() {
        return Math.max(1, MirrorConfig.CLIENT.maxRecursionDepth.get())
                * MirrorCapturePool.shaderBucketCount();
    }

    @Unique
    private ProgramSet mirror$programSetFor(NamespacedId dimension) {
        ProgramSet cached = mirror$programSetsByDimension.get(dimension);
        if (cached != null) return cached;
        ShaderPack pack = Iris.getCurrentPack().orElseThrow(() ->
                new IllegalStateException("Oculus has no active shader pack for mirror pipeline construction"));
        ProgramSet candidate = pack.getProgramSet(dimension);
        for (ProgramSet existing : mirror$programSetGroups) {
            if (mirror$equivalentProgramSet(existing, candidate)) {
                mirror$programSetsByDimension.put(dimension, existing);
                return existing;
            }
        }
        mirror$programSetGroups.add(candidate);
        mirror$programSetsByDimension.put(dimension, candidate);
        return candidate;
    }

    @Unique
    private boolean mirror$equivalentProgramSet(ProgramSet first, ProgramSet second) {
        if (first == second) return true;
        if (first.getPack() != second.getPack()) return false;
        return mirror$programSetSignature(first).equals(mirror$programSetSignature(second));
    }

    @Unique
    private List<String> mirror$programSetSignature(ProgramSet programs) {
        List<String> cached = mirror$programSetSignatures.get(programs);
        if (cached != null) return cached;
        List<String> signature = new ArrayList<>();
        for (ProgramId id : ProgramId.values()) mirror$appendProgram(signature, programs.get(id).orElse(null));
        mirror$appendProgramArray(signature, programs.getBegin());
        mirror$appendProgramArray(signature, programs.getPrepare());
        mirror$appendProgramArray(signature, programs.getDeferred());
        mirror$appendProgramArray(signature, programs.getComposite());
        mirror$appendComputeArray(signature, programs.getSetup());
        mirror$appendComputeArray(signature, programs.getShadowCompute());
        mirror$appendComputeArrays(signature, programs.getShadowCompCompute());
        mirror$appendComputeArrays(signature, programs.getBeginCompute());
        mirror$appendComputeArrays(signature, programs.getPrepareCompute());
        mirror$appendComputeArrays(signature, programs.getDeferredCompute());
        mirror$appendComputeArrays(signature, programs.getCompositeCompute());
        mirror$appendComputeArray(signature, programs.getFinalCompute());
        cached = Collections.unmodifiableList(new ArrayList<>(signature));
        mirror$programSetSignatures.put(programs, cached);
        return cached;
    }

    @Unique
    private static void mirror$appendProgram(List<String> signature, ProgramSource source) {
        if (source == null) {
            signature.add(null);
            return;
        }
        signature.add(source.getName());
        signature.add(source.getVertexSource().orElse(null));
        signature.add(source.getGeometrySource().orElse(null));
        signature.add(source.getTessControlSource().orElse(null));
        signature.add(source.getTessEvalSource().orElse(null));
        signature.add(source.getFragmentSource().orElse(null));
    }

    @Unique
    private static void mirror$appendProgramArray(List<String> signature, ProgramSource[] sources) {
        if (sources == null) {
            signature.add(null);
            return;
        }
        signature.add(Integer.toString(sources.length));
        for (ProgramSource source : sources) mirror$appendProgram(signature, source);
    }

    @Unique
    private static void mirror$appendComputeArray(List<String> signature, ComputeSource[] sources) {
        if (sources == null) {
            signature.add(null);
            return;
        }
        signature.add(Integer.toString(sources.length));
        for (ComputeSource source : sources) {
            if (source == null) signature.add(null);
            else {
                signature.add(source.getName());
                signature.add(source.getSource().orElse(null));
            }
        }
    }

    @Unique
    private static void mirror$appendComputeArrays(List<String> signature, ComputeSource[][] sources) {
        if (sources == null) {
            signature.add(null);
            return;
        }
        signature.add(Integer.toString(sources.length));
        for (ComputeSource[] source : sources) mirror$appendComputeArray(signature, source);
    }

    @Override
    public void mirror$quarantineShaderGeneration(WorldRenderingPipeline failedPipeline, Throwable failure) {
        ProgramSet failedSet = null;
        MirrorPipelineSlotKey failedKey = null;
        for (Map.Entry<MirrorPipelineSlotKey, MirrorPipelineState> entry : mirror$mirrorPipelines.entrySet()) {
            if (entry.getValue().pipeline() == failedPipeline) {
                failedSet = entry.getKey().programSet();
                failedKey = entry.getKey();
                break;
            }
        }
        if (failedSet == null) return;
        if (!mirror$shaderGenerationFailed.add(failedSet)) return;
        MirrorDiagnostics.recordLateShaderQuarantine();
        Iris.logger.error("Late mirror terrain shader failure for program set {} and slot {}; "
                        + "shader generation {} is quarantined for secondary mirror pipelines.",
                failedKey.label(), failedKey.slot(), mirror$shaderGeneration, failure);
    }

    @Override
    public void mirror$beginTerrainProgramBuild(WorldRenderingPipeline terrainPipeline) {
        for (Map.Entry<MirrorPipelineSlotKey, MirrorPipelineState> entry : mirror$mirrorPipelines.entrySet()) {
            MirrorPipelineState state = entry.getValue();
            if (state.pipeline() != terrainPipeline || state.terrainReady()) continue;
            if (state.heavyWorkFrame() == mirror$outerFrameSequence) return;
            if (mirror$heavyBuildBudget <= 0) {
                MirrorDiagnostics.recordDeferredPipelineBuild();
                throw new MirrorPipelineUnavailableException(
                        "Mirror terrain-program warm-up deferred by the per-frame heavyweight build budget for "
                                + entry.getKey().slot(), null);
            }
            mirror$heavyBuildBudget = 0;
            state.setHeavyWorkFrame(mirror$outerFrameSequence);
            return;
        }
    }

    @Override
    public void mirror$markTerrainProgramsNotReady(WorldRenderingPipeline invalidPipeline) {
        for (MirrorPipelineState state : mirror$mirrorPipelines.values()) {
            if (state.pipeline() == invalidPipeline) state.markTerrainNotReady();
        }
    }

    @Override
    public void mirror$releaseMirrorView(long viewId) {
        for (MirrorPipelineState state : mirror$mirrorPipelines.values()) {
            if (state.pipeline() instanceof MirrorTemporalStateAccess temporalState) {
                temporalState.mirror$releaseView(viewId);
            }
        }
    }

    @Unique
    private void mirror$destroyMirrorPipelines(String reason) {
        for (MirrorPipelineState state : mirror$mirrorPipelines.values()) {
            if (state.pipeline() == pipeline) {
                throw new IllegalStateException("Cannot destroy the active mirror pipeline");
            }
        }

        mirror$mirrorPipelines.forEach((key, state) -> {
            Iris.logger.info("Destroying mirror pipeline for shader generation {}, program set {}, slot {} ({})",
                    mirror$shaderGeneration, key.label(), key.slot(), reason);
            OculusTerrainProgramCacheAccess.destroyPipeline(state.pipeline());
            state.pipeline().destroy();
        });
        mirror$mirrorPipelines.clear();
        mirror$prewarmQueues.clear();
        mirror$prewarmInitialized.clear();
        mirror$programSetGroups.clear();
        mirror$programSetsByDimension.clear();
        mirror$programSetSignatures.clear();
        mirror$failedMirrorSlots.clear();
        mirror$shaderGenerationFailed.clear();
        mirror$heavyBuildBudget = 1;
        mirror$frameHadMirrorDemand = false;
        mirror$frameDemandProgramSet = null;
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
