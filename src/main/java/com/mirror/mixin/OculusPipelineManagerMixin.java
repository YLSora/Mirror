package com.mirror.mixin;

import com.mirror.client.MirrorCustomImageSharing;
import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPipelineBuildContext;
import com.mirror.client.MirrorRenderState;
import com.mirror.client.MirrorShaderPolicy;
import com.mirror.client.MirrorPassContext;
import com.mirror.client.MirrorPipelineAccess;
import com.mirror.client.MirrorPipelineSlotKey;
import com.mirror.client.MirrorPipelineState;
import com.mirror.client.MirrorPipelineUnavailableException;
import com.mirror.client.MirrorShadowBorrowCapability;
import com.mirror.client.MirrorTemporalStateAccess;
import com.mirror.client.OculusCompat;
import com.mirror.client.OculusTerrainProgramCacheAccess;
import com.mirror.config.MirrorConfig;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the secondary Oculus pipelines for the current ProgramSet.
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
    private final List<ProgramSet> mirror$programSetGroups = new ArrayList<>();

    @Unique
    private final Map<NamespacedId, ProgramSet> mirror$programSetsByDimension = new HashMap<>();

    @Unique
    private final Map<ProgramSet, List<String>> mirror$programSetSignatures = new IdentityHashMap<>();

    @Unique
    private long mirror$shaderGeneration;

    @Unique
    private ProgramSet mirror$activeProgramSet;

    @Unique
    private MirrorPipelineSlotKey[] mirror$activeKeys = new MirrorPipelineSlotKey[0];

    @Unique
    private boolean mirror$failedLayerPendingCleanup;

    @Unique
    private final MirrorShaderPolicy mirror$policy = new MirrorShaderPolicy();

    @Unique
    private final Map<Integer, Long> mirror$lastRejectionLog = new HashMap<>();

    @Inject(method = "preparePipeline", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$preparePipeline(NamespacedId realDimension,
                                        CallbackInfoReturnable<WorldRenderingPipeline> callback) {
        if (!OculusCompat.isMirrorPass() || !OculusCompat.isShaderPackInUse()) return;

        try {
            ProgramSet programs = mirror$programSetFor(realDimension);
            int depth = MirrorPassContext.current().recursionDepth();
            if (programs != mirror$activeProgramSet) throw new MirrorPipelineUnavailableException("No active mirror ProgramSet", null);
            if (depth >= mirror$layerLimit() || depth >= mirror$activeKeys.length) {
                mirror$reject(2, "Mirror shader layer {} exceeds the prepared pipeline limit", depth);
                throw new MirrorPipelineUnavailableException("Mirror layer not prepared", null);
            }
            WorldRenderingPipeline owner = pipeline;
            MirrorPipelineState state = mirror$ensurePipeline(mirror$activeKeys[depth], owner);
            if (state == null) throw new MirrorPipelineUnavailableException("Mirror pipeline unavailable", null);
            pipeline = state.pipeline();
            callback.setReturnValue(state.pipeline());
        } catch (Throwable failure) {
            mirror$reject(1, "Mirror shader pipeline selection failed; rejecting pass without vanilla fallback", failure);
            throw failure instanceof MirrorPipelineUnavailableException unavailable ? unavailable
                    : new MirrorPipelineUnavailableException("Mirror shader pipeline selection failed", failure);
        }
    }

    @Inject(method = "preparePipeline", at = @At("RETURN"), require = 1, remap = false)
    private void mirror$prepareDirectPipeline(NamespacedId dimension,
                                               CallbackInfoReturnable<WorldRenderingPipeline> callback) {
        if (OculusCompat.isMirrorPass() || Minecraft.getInstance().level == null) return;
        try {
            if (!(callback.getReturnValue() instanceof IrisRenderingPipeline)) {
                if (mirror$activeProgramSet != null) {
                    mirror$destroyMirrorPipelines("no shader primary pipeline");
                    mirror$activeProgramSet = null;
                    mirror$policy.activateProgramSet(null);
                }
                return;
            }
            ProgramSet programs = mirror$programSetFor(dimension);
            int limit = mirror$layerLimit();
            boolean changedPrograms = mirror$activeProgramSet != programs;
            boolean changedLimit = limit != mirror$activeKeys.length;
            if (changedPrograms || changedLimit) {
                MirrorPipelineSlotKey[] keys = new MirrorPipelineSlotKey[limit];
                for (int depth = 0; depth < limit; depth++) keys[depth] = new MirrorPipelineSlotKey(programs, depth);
                if (changedPrograms) {
                    mirror$destroyMirrorPipelines("different ProgramSet");
                    mirror$policy.activateProgramSet(programs);
                    mirror$activeProgramSet = programs;
                } else if (changedLimit) {
                    mirror$pruneLayers(limit);
                }
                mirror$activeKeys = keys;
            }
            if (mirror$failedLayerPendingCleanup) mirror$pruneLayers(limit);

            boolean prewarmRecursive = MirrorConfig.CLIENT.prewarmRecursiveMirrorPipelines.get()
                    && MirrorConfig.CLIENT.recursionMode.get() == MirrorConfig.RecursionMode.RECURSIVE;
            MirrorPipelineState direct = mirror$mirrorPipelines.get(mirror$activeKeys[0]);
            if (direct != null && (!prewarmRecursive || mirror$mirrorPipelines.size() == limit)) return;
            WorldRenderingPipeline owner = callback.getReturnValue();
            if (direct == null) direct = mirror$ensurePipeline(mirror$activeKeys[0], owner);
            if (direct == null) return;
            if (direct.terrainReady() && prewarmRecursive) {
                for (int depth = 1; depth < limit; depth++) {
                    MirrorPipelineState state = mirror$ensurePipeline(mirror$activeKeys[depth], owner);
                    if (state == null || !state.terrainReady()) break;
                }
            }
        } catch (Throwable failure) {
            mirror$reject(8, "Could not prepare direct mirror pipeline", failure);
        }
    }

    @Unique
    private void mirror$pruneLayers(int limit) {
        var iterator = mirror$mirrorPipelines.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            boolean failed = entry.getValue().failed();
            int depth = entry.getKey().recursionDepth();
            if (!failed && depth < limit) continue;
            iterator.remove();
            mirror$destroyLayer(entry.getValue().pipeline(), depth,
                    failed ? "terrain compilation failed" : "limit changed");
        }
        mirror$failedLayerPendingCleanup = false;
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$destroyWithOculus(CallbackInfo callback) {
        boolean shaderReload = Iris.getCurrentPack().isEmpty();
        boolean borrowed = mirror$mirrorPipelines.values().stream().anyMatch(state ->
                state.pipeline() instanceof MirrorShadowBorrowCapability capability
                        && capability.mirror$dependsOnPrimary());
        if (!shaderReload && !borrowed) {
            Iris.logger.info("Keeping {} mirror pipeline slots for shader generation {} across dimension pipeline switch",
                    mirror$mirrorPipelines.size(), mirror$shaderGeneration);
            return;
        }

        String reason = shaderReload ? "shader reload" : "primary pipeline switch";
        Iris.logger.info("Destroying mirror pipeline generation {} before {}", mirror$shaderGeneration, reason);
        mirror$destroyMirrorPipelines(reason);
        if (!shaderReload) return;
        mirror$activeProgramSet = null;
        mirror$policy.resetGeneration();
        mirror$programSetGroups.clear();
        mirror$programSetsByDimension.clear();
        mirror$programSetSignatures.clear();
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
        mirror$policy.beginFrame();
    }

    @Override
    public void mirror$sealLayerSizes() {
        mirror$policy.finishFrame(System.nanoTime(),
                (long) (MirrorConfig.CLIENT.shaderCaptureShrinkDelaySeconds.get() * 1_000_000_000L));
    }

    @Override
    public boolean mirror$requestLayerSize(int depth, int size) {
        return mirror$policy.requestSize(depth, size);
    }

    @Override
    public int mirror$getLayerSize(int depth) {
        return mirror$policy.activeSize(depth);
    }

    @Override
    public void mirror$prewarmTerrainPrograms(OculusTerrainProgramCacheAccess compiler) {
        if (mirror$activeProgramSet == null) return;
        int limit = MirrorConfig.CLIENT.prewarmRecursiveMirrorPipelines.get() ? mirror$activeKeys.length : 1;
        for (int depth = 0; depth < limit; depth++) {
            MirrorPipelineState state = mirror$mirrorPipelines.get(mirror$activeKeys[depth]);
            if (state == null || state.failed() || state.terrainReady()
                    || !mirror$policy.mayAttempt(depth, System.nanoTime())) continue;
            if (!mirror$policy.claimBuild(depth)) return;
            WorldRenderingPipeline saved = pipeline;
            MirrorRenderState renderState = null;
            try {
                renderState = MirrorRenderState.capture();
                pipeline = state.pipeline();
                long started = System.nanoTime();
                if (compiler.mirror$prewarmPipeline(state.pipeline())) {
                    mirror$markTerrainProgramsReady(state.pipeline(), System.nanoTime() - started);
                }
            } catch (Throwable failure) {
                mirror$recordLayerFailure(state.pipeline(), failure);
            } finally {
                pipeline = saved;
                mirror$restoreGl(renderState);
            }
            return;
        }
    }

    @Override
    public void mirror$logout() {
        mirror$destroyMirrorPipelines("logout");
        mirror$activeProgramSet = null;
        mirror$policy.logout();
    }

    @Override
    public void mirror$markTerrainProgramsReady(WorldRenderingPipeline readyPipeline, long compileNanos) {
        for (Map.Entry<MirrorPipelineSlotKey, MirrorPipelineState> entry : mirror$mirrorPipelines.entrySet()) {
            MirrorPipelineState state = entry.getValue();
            if (state.pipeline() != readyPipeline || state.terrainReady()) continue;
            state.markTerrainReady();
            long totalWarmupNanos = System.nanoTime() - state.constructionStartedNanos();
            MirrorDiagnostics.terrainProgramsReady(
                    entry.getKey().label(), entry.getKey().recursionDepth(), compileNanos, totalWarmupNanos);
            return;
        }
    }

    @Unique
    private MirrorPipelineState mirror$ensurePipeline(MirrorPipelineSlotKey key, WorldRenderingPipeline owner) {
        int depth = key.recursionDepth();
        MirrorPipelineState existing = mirror$mirrorPipelines.get(key);
        if (existing != null && existing.pipeline() instanceof MirrorShadowBorrowCapability capability
                && capability.mirror$dependsOnPrimary() && existing.owner() != owner) {
            mirror$mirrorPipelines.remove(key);
            mirror$destroyLayer(existing.pipeline(), depth, "primary pipeline owner changed");
            existing = null;
        }
        if (existing != null) return existing.failed() ? null : existing;
        if (!mirror$mayBuild(depth)) return null;
        if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
            mirror$reject(7, "Mirror pipeline build deferred while Oculus terrain reload is pending at depth {}", depth);
            return null;
        }
        if (!mirror$policy.claimConstruction(depth)) {
            mirror$reject(4, "Mirror pipeline build deferred by per-frame budget at depth {}", depth);
            return null;
        }

        IrisRenderingPipeline built = null;
        MirrorPipelineBuildContext build = null;
        MirrorRenderState renderState = null;
        WorldRenderingPipeline saved = pipeline;
        long started = System.nanoTime();
        try {
            renderState = MirrorRenderState.capture();
            int mirrorShadowResolution = key.programSet().getPackDirectives().getShadowDirectives().getResolution();
            if (mirror$policy.mayBorrowShadowTargets()) {
                build = MirrorPipelineBuildContext.begin(depth, mirror$policy.activeSize(depth), owner,
                        mirrorShadowResolution, MirrorConfig.CLIENT.shareShadowTargetsWithPrimary.get());
            } else {
                build = MirrorPipelineBuildContext.beginIndependent(depth, mirror$policy.activeSize(depth), owner,
                        "borrow failed earlier this generation: " + mirror$policy.shadowBorrowFailureClass());
            }
            build.configureCustomTextureSharing(key.programSet().getPack(),
                    key.programSet().getPackDirectives().getNoiseTextureResolution(),
                    MirrorConfig.CLIENT.shareCustomTexturesWithPrimary.get(),
                    mirror$policy.mayBorrowCustomTextures() ? null : mirror$policy.customTextureBorrowFailureClass());
            build.configureCustomImageSharing(key.programSet(), mirror$customImageSources(key.programSet(), owner),
                    MirrorConfig.CLIENT.shareCustomImagesWithPrimary.get(),
                    mirror$policy.mayBorrowCustomImages() ? null : mirror$policy.customImageBorrowFailureClass(),
                    ModList.get().isLoaded("voxy"));
            boolean borrowing = build.borrowsShadowTargets();
            boolean borrowingCustomTextures = build.borrowsCustomTextureManager();
            built = new IrisRenderingPipeline(key.programSet());
            build.complete(built);
            if (built instanceof MirrorShadowBorrowCapability capability) capability.mirror$setPrimaryOwner(owner);
            if (borrowing) {
                Iris.logger.info("shadow targets: borrowed from primary (res {}, adopted {} FBOs)",
                        mirrorShadowResolution, build.adoptedFramebuffers());
            } else {
                Iris.logger.info("shadow targets: independent ({})", build.independentReason());
            }
            if (borrowingCustomTextures) {
                Iris.logger.info("custom textures: borrowed from primary");
            } else {
                Iris.logger.info("custom textures: independent ({})", build.customTextureIndependentReason());
            }
            Iris.logger.info("custom images: shared {} / independent {}",
                    build.sharedCustomImageNames(), build.independentCustomImageNames());
            Iris.logger.info("custom image scan: {}", build.customImageScanSummary());
            MirrorPipelineState state = new MirrorPipelineState(built, owner, started);
            mirror$mirrorPipelines.put(key, state);
            Iris.logger.info("Mirror shader pipelines: {}/{} (created depth {})",
                    mirror$mirrorPipelines.size(), MirrorConfig.CLIENT.maxShaderMirrorPipelines.get(), depth);
            MirrorDiagnostics.pipelineConstructed(key.label(), depth, System.nanoTime() - started);
            return state;
        } catch (Throwable failure) {
            if (build != null && build.borrowsShadowTargets()) {
                mirror$policy.shadowBorrowFailed(failure.getClass().getSimpleName());
            }
            if (build != null && build.borrowsCustomTextureManager()) {
                mirror$policy.customTextureBorrowFailed(failure.getClass().getSimpleName());
            }
            if (build != null && build.borrowsCustomImages()) {
                mirror$policy.customImageBorrowFailed(failure.getClass().getSimpleName());
            }
            try {
                mirror$fail(depth, failure, "constructing mirror pipeline");
            } finally {
                mirror$mirrorPipelines.remove(key);
                if (built != null) mirror$destroyLayer(built, depth, "failed to install");
                else if (build != null) build.rollback();
            }
            return null;
        } finally {
            pipeline = saved;
            try {
                if (build != null) build.close();
                WorldRenderingSettings.INSTANCE.clearReloadRequired();
            } finally {
                mirror$restoreGl(renderState);
            }
        }
    }

    @Unique
    private int mirror$layerLimit() {
        return MirrorShaderPolicy.maxActiveDepth(MirrorConfig.CLIENT.maxRecursionDepth.get(),
                MirrorConfig.CLIENT.maxShaderMirrorPipelines.get());
    }

    @Unique
    private boolean mirror$mayBuild(int depth) {
        if (mirror$policy.mayAttempt(depth, System.nanoTime())) return true;
        if (mirror$policy.failureCount(depth) >= 3) {
            mirror$reject(5, "Mirror shader layer {} abandoned until shader reload", depth);
        } else {
            mirror$reject(6, "Mirror shader layer {} is cooling down", depth);
        }
        return false;
    }

    @Unique
    private void mirror$fail(int depth, Throwable failure, String operation) {
        mirror$policy.failed(depth, System.nanoTime(), 30_000_000_000L);
        mirror$logFailure(depth, failure, operation);
    }

    @Unique
    private void mirror$logFailure(int depth, Throwable failure, String operation) {
        Iris.logger.error("Mirror shader layer {} failed while {}; refusing unshaded fallback", depth,
                operation, failure);
    }

    @Unique
    private void mirror$reject(int reason, String message, Object detail) {
        long now = System.nanoTime();
        long previous = mirror$lastRejectionLog.getOrDefault(reason, Long.MIN_VALUE);
        if (previous != Long.MIN_VALUE && now - previous < 10_000_000_000L) return;
        mirror$lastRejectionLog.put(reason, now);
        Iris.logger.warn(message, detail);
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
    private static List<MirrorCustomImageSharing.Source> mirror$customImageSources(
            ProgramSet programs, WorldRenderingPipeline primaryPipeline) {
        List<MirrorCustomImageSharing.Source> sources = new ArrayList<>();
        for (ProgramId id : ProgramId.values()) {
            if (!MirrorCustomImageSharing.scansProgram(id.getGroup().name(), id.name())) continue;
            if (id == ProgramId.DhGeneric) {
                if (primaryPipeline instanceof IrisRenderingPipeline irisPipeline) {
                    ProgramSource terrain = programs.getDhTerrain().orElse(null);
                    irisPipeline.getDHGenericShader().filter(program -> terrain == null
                                    || !program.getName().equals(terrain.getName()))
                            .ifPresent(program -> mirror$appendCustomImageProgram(sources, program));
                }
                continue;
            }
            programs.get(id).ifPresent(program -> mirror$appendCustomImageProgram(sources, program));
        }
        mirror$appendCustomImageProgramArray(sources, programs.getBegin());
        mirror$appendCustomImageProgramArray(sources, programs.getPrepare());
        mirror$appendCustomImageProgramArray(sources, programs.getDeferred());
        mirror$appendCustomImageProgramArray(sources, programs.getComposite());
        mirror$appendCustomImageComputeArray(sources, "setup", programs.getSetup());
        mirror$appendCustomImageComputeArrays(sources, "begin compute", programs.getBeginCompute());
        mirror$appendCustomImageComputeArrays(sources, "prepare compute", programs.getPrepareCompute());
        mirror$appendCustomImageComputeArrays(sources, "deferred compute", programs.getDeferredCompute());
        mirror$appendCustomImageComputeArrays(sources, "composite compute", programs.getCompositeCompute());
        mirror$appendCustomImageComputeArray(sources, "final compute", programs.getFinalCompute());
        return sources;
    }

    @Unique
    private static void mirror$appendCustomImageProgramArray(
            List<MirrorCustomImageSharing.Source> sources, ProgramSource[] programs) {
        if (programs == null) return;
        for (ProgramSource program : programs) if (program != null) mirror$appendCustomImageProgram(sources, program);
    }

    @Unique
    private static void mirror$appendCustomImageProgram(
            List<MirrorCustomImageSharing.Source> sources, ProgramSource program) {
        String name = program.getName();
        program.getVertexSource().ifPresent(text -> sources.add(new MirrorCustomImageSharing.Source(name + " vertex", text)));
        program.getGeometrySource().ifPresent(text -> sources.add(new MirrorCustomImageSharing.Source(name + " geometry", text)));
        program.getTessControlSource().ifPresent(text -> sources.add(new MirrorCustomImageSharing.Source(name + " tessellation control", text)));
        program.getTessEvalSource().ifPresent(text -> sources.add(new MirrorCustomImageSharing.Source(name + " tessellation evaluation", text)));
        program.getFragmentSource().ifPresent(text -> sources.add(new MirrorCustomImageSharing.Source(name + " fragment", text)));
    }

    @Unique
    private static void mirror$appendCustomImageComputeArray(
            List<MirrorCustomImageSharing.Source> sources, String group, ComputeSource[] programs) {
        if (programs == null) return;
        for (ComputeSource program : programs) {
            if (program != null) program.getSource().ifPresent(text -> sources.add(
                    new MirrorCustomImageSharing.Source(group + " / " + program.getName(), text)));
        }
    }

    @Unique
    private static void mirror$appendCustomImageComputeArrays(
            List<MirrorCustomImageSharing.Source> sources, String group, ComputeSource[][] programs) {
        if (programs == null) return;
        for (int i = 0; i < programs.length; i++) mirror$appendCustomImageComputeArray(sources,
                group + " " + i, programs[i]);
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
    public void mirror$beginTerrainProgramBuild(WorldRenderingPipeline terrainPipeline) {
        for (Map.Entry<MirrorPipelineSlotKey, MirrorPipelineState> entry : mirror$mirrorPipelines.entrySet()) {
            MirrorPipelineState state = entry.getValue();
            if (state.pipeline() != terrainPipeline || state.terrainReady()) continue;
            if (!mirror$mayBuild(entry.getKey().recursionDepth())) {
                throw new MirrorPipelineUnavailableException("Mirror terrain programs are cooling down", null);
            }
            if (!mirror$policy.claimBuild(entry.getKey().recursionDepth())) {
                MirrorDiagnostics.recordDeferredPipelineBuild();
                mirror$reject(4, "Mirror terrain build deferred by per-frame budget at depth {}", entry.getKey().recursionDepth());
                throw new MirrorPipelineUnavailableException(
                        "Mirror terrain-program warm-up deferred by the per-frame heavyweight build budget for "
                                + entry.getKey().recursionDepth(), null);
            }
            return;
        }
    }

    @Override
    public void mirror$recordLayerFailure(WorldRenderingPipeline failedPipeline, Throwable failure) {
        for (Map.Entry<MirrorPipelineSlotKey, MirrorPipelineState> entry : mirror$mirrorPipelines.entrySet()) {
            if (entry.getValue().pipeline() == failedPipeline) {
                // Never destroy the active pipeline from inside its terrain pass.
                entry.getValue().markFailed();
                mirror$failedLayerPendingCleanup = true;
                mirror$fail(entry.getKey().recursionDepth(), failure, "compiling terrain programs");
                return;
            }
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
        var iterator = mirror$mirrorPipelines.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            mirror$destroyLayer(entry.getValue().pipeline(), entry.getKey().recursionDepth(), reason);
        }
        mirror$failedLayerPendingCleanup = false;
    }

    @Unique
    private void mirror$destroyLayer(WorldRenderingPipeline old, int depth, String reason) {
        MirrorRenderState currentState = null;
        try {
            currentState = MirrorRenderState.capture();
        } catch (Throwable failure) {
            Iris.logger.error("Could not snapshot the GL state before mirror pipeline destruction", failure);
        }
        try {
            OculusTerrainProgramCacheAccess.destroyPipeline(old);
        } catch (Throwable failure) {
            Iris.logger.error("Failed to release mirror terrain programs at depth {}", depth, failure);
        } finally {
            try {
                old.destroy();
            } catch (Throwable failure) {
                Iris.logger.error("Failed to destroy mirror pipeline at depth {}", depth, failure);
            } finally {
                mirror$restoreGl(currentState);
            }
        }
        Iris.logger.info("Mirror shader pipelines: {}/{} (destroyed depth {}, {})",
                mirror$mirrorPipelines.size(), MirrorConfig.CLIENT.maxShaderMirrorPipelines.get(), depth, reason);
    }

    @Unique
    private void mirror$restoreGl(MirrorRenderState previous) {
        try {
            try {
                if (pipeline instanceof MirrorTemporalStateAccess state) state.mirror$bindStorageBuffers();
            } finally {
                if (previous != null) previous.restore();
            }
        } catch (Throwable failure) {
            Iris.logger.error("Failed to restore GL bindings after mirror pipeline maintenance", failure);
        }
    }
}
