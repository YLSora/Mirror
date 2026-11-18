package com.mirror.mixin;

import com.mirror.client.MirrorPipelineAccess;
import com.mirror.client.MirrorPipelineUnavailableException;
import com.mirror.client.OculusCompat;
import com.mirror.client.OculusShaderSourceCompatibility;
import com.mirror.client.OculusTerrainProgramCacheAccess;
import com.mirror.client.OculusTerrainProgramCacheStore;
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
import java.util.Locale;
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
    private WorldRenderingPipeline mirror$primaryPipeline;

    @Unique
    private ChunkVertexType mirror$primaryVertexType;

    @Unique
    private EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> mirror$primaryPrograms;

    @Unique
    private boolean mirror$primaryShadersCreated;

    @Unique
    private int mirror$knownGlobalVersion = Integer.MIN_VALUE;

    @Unique
    private long mirror$terrainCreationStartNanos;

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

    @Inject(method = "createShaders", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$beginTerrainProgramCreation(SodiumTerrainPipeline terrainPipeline,
                                                     ChunkVertexType vertexType,
                                                     CallbackInfo callback) {
        mirror$terrainCreationStartNanos = 0L;
        if (!OculusCompat.isMirrorPass()) return;

        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        if (current != null && Iris.getPipelineManager() instanceof MirrorPipelineAccess access) {
            access.mirror$beginTerrainProgramBuild(current);
        }
        mirror$terrainCreationStartNanos = System.nanoTime();
    }

    @Inject(method = "createShaders", at = @At("RETURN"), require = 1, remap = false)
    private void mirror$finishTerrainProgramCreation(SodiumTerrainPipeline terrainPipeline,
                                                      ChunkVertexType vertexType,
                                                      CallbackInfo callback) {
        long started = mirror$terrainCreationStartNanos;
        mirror$terrainCreationStartNanos = 0L;
        if (started == 0L || !OculusCompat.isMirrorPass()) return;

        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        if (current != null && mirror$activeVertexType != null
                && Iris.getPipelineManager() instanceof MirrorPipelineAccess access) {
            access.mirror$markTerrainProgramsReady(current, System.nanoTime() - started);
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

        try {
            instance.createShaders(terrainPipeline, vertexType);
        } catch (RuntimeException failure) {
            if (!mirror$isShaderCompileOrLinkFailure(failure)) throw failure;
            mirror$deletePrograms(programs);
            programs = new EnumMap<>(IrisTerrainPass.class);
            shadersCreated = false;
            mirror$terrainCreationStartNanos = 0L;

            WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
            if (current != null && Iris.getPipelineManager() instanceof MirrorPipelineAccess access) {
                access.mirror$quarantineShaderGeneration(current, failure);
            }
            throw new MirrorPipelineUnavailableException(
                    "Oculus could not compile/link terrain shaders for the secondary mirror pipeline", failure);
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
            if (lookup.vertexTypeMismatch()
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
        } else if (!mirrorPass && current == mirror$primaryPipeline
                && vertexType == mirror$primaryVertexType && mirror$primaryPrograms != null) {
            programs = mirror$primaryPrograms;
            shadersCreated = mirror$primaryShadersCreated;
        } else {
            if (!mirrorPass && current != mirror$primaryPipeline) mirror$deletePrimaryPrograms();
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
        programs = mirror$primaryPrograms == null
                ? new EnumMap<>(IrisTerrainPass.class) : mirror$primaryPrograms;
        shadersCreated = mirror$primaryShadersCreated;
        mirror$primaryPrograms = null;
        mirror$primaryPipeline = null;
        mirror$primaryVertexType = null;
        mirror$activePipeline = null;
        mirror$activeVertexType = null;
        mirror$activePipelineIsMirror = false;
        mirror$terrainCreationStartNanos = 0L;
    }

    @Override
    public void mirror$clearMirrorPrograms() {
        // The static lifecycle entry clears the process cache first. This only deletes the current
        // active mirror map; primary programs remain under Oculus' normal pipeline lifecycle.
        if (mirror$activePipelineIsMirror) {
            mirror$deletePrograms(programs);
            programs = new EnumMap<>(IrisTerrainPass.class);
            shadersCreated = false;
        }
        mirror$activePipeline = null;
        mirror$activeVertexType = null;
        mirror$activePipelineIsMirror = false;
        mirror$terrainCreationStartNanos = 0L;
    }

    @Unique
    private void mirror$storeActiveMirrorPrograms() {
        if (!mirror$activePipelineIsMirror || mirror$activePipeline == null || mirror$activeVertexType == null) return;
        OculusTerrainProgramCacheAccess.put(
                mirror$activePipeline, mirror$activeVertexType, programs, shadersCreated);
    }

    @Unique
    private void mirror$storeActivePrimaryPrograms() {
        if (mirror$activePipelineIsMirror || mirror$activePipeline == null) return;
        if (mirror$primaryPrograms != null && mirror$primaryPrograms != programs) {
            mirror$deletePrograms(mirror$primaryPrograms);
        }
        mirror$primaryPipeline = mirror$activePipeline;
        mirror$primaryVertexType = mirror$activeVertexType;
        mirror$primaryPrograms = programs;
        mirror$primaryShadersCreated = shadersCreated;
    }

    @Unique
    private void mirror$deletePrimaryPrograms() {
        if (mirror$primaryPrograms != null) {
            mirror$deletePrograms(mirror$primaryPrograms);
            mirror$primaryPrograms = null;
        }
        mirror$primaryPipeline = null;
        mirror$primaryVertexType = null;
        mirror$primaryShadersCreated = false;
    }

    @Unique
    private static boolean mirror$isShaderCompileOrLinkFailure(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            String className = cursor.getClass().getName();
            if (className.equals("net.irisshaders.iris.gl.shader.ShaderCompileException")) return true;

            String message = cursor.getMessage();
            if (message == null) continue;
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("shader compilation failed")
                    || normalized.contains("shader compile failed")
                    || normalized.contains("failed to compile shader")
                    || normalized.contains("couldn't compile")
                    || normalized.contains("could not compile")
                    || normalized.contains("shader linking failed")
                    || normalized.contains("shader program linking failed")
                    || normalized.contains("failed to link shader")) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static void mirror$deletePrograms(Map<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> values) {
        for (GlProgram<?> program : values.values()) {
            if (program != null) program.delete();
        }
        values.clear();
    }
}
