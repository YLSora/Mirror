package com.mirror.mixin;

import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPipelineAccess;
import com.mirror.client.MirrorPipelineUnavailableException;
import com.mirror.client.OculusCompat;
import com.mirror.client.OculusShaderSourceCompatibility;
import com.mirror.client.OculusTerrainProgramCacheAccess;
import com.mirror.client.OculusTerrainProgramCacheStore;
import com.mirror.client.MirrorPrimaryProgramStash;
import com.mirror.client.OculusTerrainProgramState;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkProgramOverrides;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkShaderInterface;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisTerrainPass;
import net.irisshaders.iris.pipeline.SodiumTerrainPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.Map;

/** Backports Iris' per-pipeline terrain program selection to Oculus 1.8.0. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkProgramOverrides",
        remap = false)
abstract class OculusTerrainProgramCacheMixin implements OculusTerrainProgramCacheAccess {
    @Shadow
    @Final
    @Mutable
    private EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> programs;

    @Shadow
    private boolean shadersCreated;

    @Shadow
    private int versionCounterForSodiumShaderReload;

    @Unique
    private WorldRenderingPipeline mirror$activePipeline;

    @Unique
    private ChunkVertexType mirror$activeVertexType;

    @Unique
    private boolean mirror$activePipelineIsMirror;

    @Unique
    private final MirrorPrimaryProgramStash<OculusTerrainProgramState> mirror$primaryPrograms =
            new MirrorPrimaryProgramStash<>(OculusTerrainProgramCacheAccess::deleteState);

    @Unique
    private int mirror$knownGlobalVersion = Integer.MIN_VALUE;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, remap = false)
    private void mirror$registerCache(CallbackInfo callback) {
        OculusTerrainProgramCacheAccess.register((OculusTerrainProgramCacheAccess) (Object) this);
    }

    // TransformPatcher.patchVanilla() is not used for Sodium/Embeddium terrain shaders. Patch the
    // final source immediately before each GlShader is compiled.
    @ModifyArg(method = "createVertexShader", at = @At(value = "INVOKE", target =
            "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"), index = 2, require = 1, remap = false)
    private String mirror$patchTerrainVertexSource(String source) { return OculusShaderSourceCompatibility.patch(source); }

    @ModifyArg(method = "createGeometryShader", at = @At(value = "INVOKE", target =
            "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"), index = 2, require = 1, remap = false)
    private String mirror$patchTerrainGeometrySource(String source) { return OculusShaderSourceCompatibility.patch(source); }

    @ModifyArg(method = "createTessControlShader", at = @At(value = "INVOKE", target =
            "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"), index = 2, require = 1, remap = false)
    private String mirror$patchTerrainTessControlSource(String source) { return OculusShaderSourceCompatibility.patch(source); }

    @ModifyArg(method = "createTessEvalShader", at = @At(value = "INVOKE", target =
            "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"), index = 2, require = 1, remap = false)
    private String mirror$patchTerrainTessEvalSource(String source) { return OculusShaderSourceCompatibility.patch(source); }

    @ModifyArg(method = "createFragmentShader", at = @At(value = "INVOKE", target =
            "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"), index = 2, require = 1, remap = false)
    private String mirror$patchTerrainFragmentSource(String source) { return OculusShaderSourceCompatibility.patch(source); }

    @Inject(method = "getProgramOverride", at = @At("RETURN"), require = 1, remap = false)
    private void mirror$warmAfterPrimaryTerrain(TerrainRenderPass pass, ChunkVertexType vertexType,
            CallbackInfoReturnable<GlProgram<IrisChunkShaderInterface>> callback) {
        // Use the real renderer's vertex type, including after an Embeddium renderer rebuild.
        if (!OculusCompat.isMirrorPass() && !OculusCompat.isShadowPass() && OculusCompat.isShaderPackInUse()
                && Iris.getPipelineManager() instanceof MirrorPipelineAccess access) {
            try {
                access.mirror$prewarmTerrainPrograms(this);
            } catch (RuntimeException | LinkageError failure) {
                MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.TERRAIN_PREWARM_FAILED, failure);
            }
        }
    }

    // createShaders() is lazy and can fail well after IrisRenderingPipeline construction. Guard only
    // this optional mirror invocation; main-world terrain compilation remains untouched.
    @Redirect(method = "getProgramOverride", at = @At(value = "INVOKE", target =
            "Lnet/irisshaders/iris/compat/sodium/impl/shader_overrides/IrisChunkProgramOverrides;createShaders(Lnet/irisshaders/iris/pipeline/SodiumTerrainPipeline;Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"), require = 1, remap = false)
    private void mirror$guardLateTerrainShaderCreation(IrisChunkProgramOverrides instance,
                                                        SodiumTerrainPipeline terrainPipeline,
                                                        ChunkVertexType vertexType) {
        if (!OculusCompat.isMirrorPass()) {
            instance.createShaders(terrainPipeline, vertexType);
            return;
        }

        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        MirrorPipelineAccess access = (MirrorPipelineAccess) (Object) Iris.getPipelineManager();
        access.mirror$beginTerrainProgramBuild(current);
        long started = System.nanoTime();
        try {
            instance.createShaders(terrainPipeline, vertexType);
            access.mirror$markTerrainProgramsReady(current, System.nanoTime() - started);
        } catch (Throwable failure) {
            try {
                mirror$deletePrograms(programs);
            } finally {
                shadersCreated = false;
                access.mirror$recordLayerFailure(current, failure);
            }
            throw new MirrorPipelineUnavailableException(
                    "Oculus could not compile terrain shaders for the secondary mirror pipeline", failure);
        }
    }

    @Inject(method = "getProgramOverride", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$activateCurrentPipeline(TerrainRenderPass pass, ChunkVertexType vertexType,
                                                CallbackInfoReturnable<GlProgram<IrisChunkShaderInterface>> callback) {
        int globalVersion = Iris.getPipelineManager().getVersionCounterForSodiumShaderReload();
        if (mirror$knownGlobalVersion != globalVersion) {
            // PipelineManager increments this counter for dimension switches as well as shader
            // reloads. Keep live mirror programs in the process cache across the former, but
            // dispose all primary programs because this override instance is no longer valid.
            mirror$storeActiveMirrorPrograms();
            mirror$storeActivePrimaryPrograms();
            mirror$deletePrimaryPrograms();
            programs = new EnumMap<>(IrisTerrainPass.class);
            shadersCreated = false;
            versionCounterForSodiumShaderReload = globalVersion;
            mirror$activePipeline = null;
            mirror$activeVertexType = null;
            mirror$activePipelineIsMirror = false;
            mirror$knownGlobalVersion = globalVersion;
        }

        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        boolean mirrorPass = OculusCompat.isMirrorPass();
        if (current == mirror$activePipeline && vertexType == mirror$activeVertexType
                && mirrorPass == mirror$activePipelineIsMirror) return;

        mirror$storeActiveMirrorPrograms();
        mirror$storeActivePrimaryPrograms();

        if (mirrorPass && current != null) {
            OculusTerrainProgramCacheStore.Lookup<OculusTerrainProgramState> lookup =
                    OculusTerrainProgramCacheAccess.take(current, vertexType);
            if (lookup.value() == null
                    && Iris.getPipelineManager() instanceof MirrorPipelineAccess access) {
                access.mirror$markTerrainProgramsNotReady(current);
            }
            OculusTerrainProgramState selected = lookup.value();
            if (selected == null) {
                programs = new EnumMap<>(IrisTerrainPass.class);
                shadersCreated = false;
            } else {
                programs = selected.programs();
                shadersCreated = selected.shadersCreated();
            }
        } else if (!mirrorPass) {
            OculusTerrainProgramState primary = mirror$primaryPrograms.take(current, vertexType);
            if (primary != null) {
                programs = primary.programs();
                shadersCreated = primary.shadersCreated();
            } else {
                programs = new EnumMap<>(IrisTerrainPass.class);
                shadersCreated = false;
            }
        } else {
            programs = new EnumMap<>(IrisTerrainPass.class);
            shadersCreated = false;
        }

        versionCounterForSodiumShaderReload = globalVersion;
        mirror$activePipeline = current;
        mirror$activeVertexType = vertexType;
        mirror$activePipelineIsMirror = mirrorPass;
    }

    @Inject(method = "deleteShaders", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$preserveMirrorProgramsBeforeDelete(CallbackInfo callback) {
        // Embeddium destroys this override object during every chunk-renderer rebuild. Give the
        // original deleteShaders() the primary map when the active pipeline is mirror, so primary
        // programs are deleted exactly once while mirror programs survive in the process cache.
        if (!mirror$activePipelineIsMirror) return;
        mirror$storeActiveMirrorPrograms();
        OculusTerrainProgramState primary = mirror$primaryPrograms.drain();
        programs = primary == null ? new EnumMap<>(IrisTerrainPass.class) : primary.programs();
        shadersCreated = primary != null && primary.shadersCreated();
        mirror$activePipeline = null;
        mirror$activeVertexType = null;
        mirror$activePipelineIsMirror = false;
    }

    @Override
    public boolean mirror$prewarmPipeline(WorldRenderingPipeline mirrorPipeline) {
        if (mirror$activeVertexType == null || mirrorPipeline.getSodiumTerrainPipeline() == null) return false;
        EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> savedPrograms = programs;
        boolean savedCreated = shadersCreated;
        var pending = new EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>>(IrisTerrainPass.class);
        boolean cached = false;
        try {
            programs = pending;
            shadersCreated = false;
            ((IrisChunkProgramOverrides) (Object) this).createShaders(
                    mirrorPipeline.getSodiumTerrainPipeline(), mirror$activeVertexType);
            OculusTerrainProgramCacheAccess.put(mirrorPipeline, mirror$activeVertexType, programs, shadersCreated);
            cached = true;
            return true;
        } finally {
            programs = savedPrograms;
            shadersCreated = savedCreated;
            if (!cached) mirror$deletePrograms(pending);
        }
    }

    @Override
    public void mirror$discardMirrorPrograms(WorldRenderingPipeline old) {
        if (!mirror$activePipelineIsMirror || mirror$activePipeline != old) return;
        try {
            mirror$deletePrograms(programs);
        } finally {
            mirror$restorePrimaryAfterMirror();
        }
    }

    @Unique
    private void mirror$restorePrimaryAfterMirror() {
        programs = new EnumMap<>(IrisTerrainPass.class);
        shadersCreated = false;
        mirror$activePipeline = null;
        mirror$activeVertexType = null;
        mirror$activePipelineIsMirror = false;
    }

    @Unique
    private void mirror$storeActiveMirrorPrograms() {
        if (!mirror$activePipelineIsMirror || mirror$activePipeline == null || mirror$activeVertexType == null) return;
        OculusTerrainProgramCacheAccess.put(
                mirror$activePipeline, mirror$activeVertexType, programs, shadersCreated);
    }

    @Unique
    private void mirror$storeActivePrimaryPrograms() {
        if (mirror$activePipelineIsMirror || mirror$activePipeline == null || mirror$activeVertexType == null) return;
        mirror$primaryPrograms.store(mirror$activePipeline, mirror$activeVertexType,
                new OculusTerrainProgramState(programs, shadersCreated));
    }

    @Unique
    private void mirror$deletePrimaryPrograms() {
        mirror$primaryPrograms.clear();
    }

    @Unique
    private static void mirror$deletePrograms(Map<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> values) {
        for (GlProgram<?> program : values.values()) {
            if (program == null) continue;
            try {
                program.delete();
            } catch (Throwable failure) {
                Iris.logger.error("Failed to release a terrain program", failure);
            }
        }
        values.clear();
    }
}
