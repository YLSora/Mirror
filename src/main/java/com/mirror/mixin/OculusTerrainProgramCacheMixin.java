package com.mirror.mixin;

import com.mirror.client.MirrorPipelineAccess;
import com.mirror.client.MirrorPipelineUnavailableException;
import com.mirror.client.OculusCompat;
import com.mirror.client.OculusShaderSourceCompatibility;
import com.mirror.client.OculusTerrainProgramCacheAccess;
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
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Backports Iris' per-pipeline terrain program selection to Oculus 1.8.0, patches the final
 * Embeddium terrain GLSL source, and contains late secondary-pipeline shader failures.
 */
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
    private final Map<WorldRenderingPipeline, OculusTerrainProgramState> mirror$programsByPipeline =
            new IdentityHashMap<>();

    @Unique
    private WorldRenderingPipeline mirror$activePipeline;

    @Unique
    private int mirror$knownGlobalVersion = Integer.MIN_VALUE;

    @Unique
    private long mirror$terrainCreationStartNanos;

    @Unique
    private boolean mirror$prewarmingSpareTerrainPrograms;

    // P6: TransformPatcher.patchVanilla() is not used for Sodium/Embeddium terrain shaders. Patch
    // the final source at the last common boundary immediately before each GlShader is compiled.
    @ModifyArg(method = "createVertexShader",
            at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"),
            index = 2, require = 1, remap = false)
    private String mirror$patchTerrainVertexSource(String source) {
        return OculusShaderSourceCompatibility.patch(source);
    }

    @ModifyArg(method = "createGeometryShader",
            at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"),
            index = 2, require = 1, remap = false)
    private String mirror$patchTerrainGeometrySource(String source) {
        return OculusShaderSourceCompatibility.patch(source);
    }

    @ModifyArg(method = "createTessControlShader",
            at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"),
            index = 2, require = 1, remap = false)
    private String mirror$patchTerrainTessControlSource(String source) {
        return OculusShaderSourceCompatibility.patch(source);
    }

    @ModifyArg(method = "createTessEvalShader",
            at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"),
            index = 2, require = 1, remap = false)
    private String mirror$patchTerrainTessEvalSource(String source) {
        return OculusShaderSourceCompatibility.patch(source);
    }

    @ModifyArg(method = "createFragmentShader",
            at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/gl/shader/GlShader;<init>(Lme/jellysquid/mods/sodium/client/gl/shader/ShaderType;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)V"),
            index = 2, require = 1, remap = false)
    private String mirror$patchTerrainFragmentSource(String source) {
        return OculusShaderSourceCompatibility.patch(source);
    }

    @Inject(method = "createShaders", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$beginTerrainProgramCreation(SodiumTerrainPipeline terrainPipeline,
                                                     ChunkVertexType vertexType,
                                                     CallbackInfo callback) {
        mirror$terrainCreationStartNanos = 0L;
        if (mirror$prewarmingSpareTerrainPrograms || !OculusCompat.isMirrorPass()) return;

        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        if (current != null && Iris.getPipelineManager() instanceof MirrorPipelineAccess access) {
            // Claim the budget at the actual expensive boundary, not merely because a WARMING
            // pipeline was selected. This prevents sky-only mirror views from starving other slots.
            access.mirror$beginTerrainProgramBuild(current);
        }
        mirror$terrainCreationStartNanos = System.nanoTime();
    }

    @Inject(method = "createShaders", at = @At("RETURN"), require = 1, remap = false)
    private void mirror$finishTerrainProgramCreation(SodiumTerrainPipeline terrainPipeline,
                                                      ChunkVertexType vertexType,
                                                      CallbackInfo callback) {
        if (mirror$prewarmingSpareTerrainPrograms) return;

        long started = mirror$terrainCreationStartNanos;
        mirror$terrainCreationStartNanos = 0L;
        if (started != 0L && OculusCompat.isMirrorPass()) {
            WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
            if (current != null && Iris.getPipelineManager() instanceof MirrorPipelineAccess access) {
                access.mirror$markTerrainProgramsReady(current, System.nanoTime() - started);
            }
            return;
        }

        // The primary terrain programs are created during normal shader/world startup. Use that
        // already-expensive initialization boundary to finish the spare mirror programs as well,
        // so the first visible mirror does not pay lazy terrain compilation on a live frame.
        if (!OculusCompat.isMirrorPass()) {
            mirror$prewarmSpareTerrainPrograms(vertexType);
        }
    }

    @Unique
    private void mirror$prewarmSpareTerrainPrograms(ChunkVertexType vertexType) {
        if (!(Iris.getPipelineManager() instanceof MirrorPipelineAccess access)) return;
        WorldRenderingPipeline primaryPipeline = Iris.getPipelineManager().getPipelineNullable();
        if (primaryPipeline == null) return;
        java.util.List<WorldRenderingPipeline> prewarmed =
                access.mirror$getPrewarmedPipelinesNeedingTerrain(primaryPipeline);
        if (prewarmed.isEmpty()) return;

        IrisChunkProgramOverrides target = (IrisChunkProgramOverrides) (Object) this;
        EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> primaryPrograms = programs;
        boolean primaryShadersCreated = shadersCreated;
        WorldRenderingPipeline primaryActivePipeline = mirror$activePipeline;

        try {
            for (WorldRenderingPipeline spare : prewarmed) {
                programs = new EnumMap<>(IrisTerrainPass.class);
                shadersCreated = false;
                mirror$activePipeline = spare;
                long started = System.nanoTime();
                try {
                    mirror$prewarmingSpareTerrainPrograms = true;
                    target.createShaders(spare.getSodiumTerrainPipeline(), vertexType);
                    long elapsed = System.nanoTime() - started;
                    mirror$programsByPipeline.put(spare,
                            new OculusTerrainProgramState(programs, shadersCreated));
                    access.mirror$markTerrainProgramsReady(spare, elapsed);
                } catch (RuntimeException failure) {
                    mirror$deletePrograms(programs);
                    programs = new EnumMap<>(IrisTerrainPass.class);
                    shadersCreated = false;
                    if (mirror$isShaderCompileOrLinkFailure(failure)) {
                        access.mirror$quarantineShaderGeneration(spare, failure);
                        Iris.logger.error("Prewarmed mirror terrain shader compilation failed; "
                                + "secondary mirror pipelines are quarantined for this shader generation",
                                failure);
                        break;
                    }
                    Iris.logger.warn("Could not prewarm mirror terrain programs; "
                            + "falling back to lazy mirror terrain creation", failure);
                    break;
                } finally {
                    mirror$prewarmingSpareTerrainPrograms = false;
                }
            }
        } finally {
            programs = primaryPrograms;
            shadersCreated = primaryShadersCreated;
            mirror$activePipeline = primaryActivePipeline;
        }
    }

    // P7: createShaders is lazy and can fail well after IrisRenderingPipeline construction. Guard
    // only this optional mirror invocation. Main-world terrain compilation remains untouched.
    @Redirect(method = "getProgramOverride",
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/compat/sodium/impl/shader_overrides/IrisChunkProgramOverrides;createShaders(Lnet/irisshaders/iris/pipeline/SodiumTerrainPipeline;Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"),
            require = 1, remap = false)
    private void mirror$guardLateTerrainShaderCreation(
            IrisChunkProgramOverrides instance,
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

            // A failed createShaders() can leave successfully linked passes from earlier enum values
            // in the active map. Delete only this active/failed pipeline's partial programs before
            // unwinding; inactive primary-pipeline programs remain stored in programsByPipeline.
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
            mirror$deletePrograms(programs);
            mirror$programsByPipeline.values().forEach(state -> mirror$deletePrograms(state.programs()));
            mirror$programsByPipeline.clear();
            programs = new EnumMap<>(IrisTerrainPass.class);
            shadersCreated = false;
            versionCounterForSodiumShaderReload = globalVersion;
            mirror$activePipeline = null;
            mirror$knownGlobalVersion = globalVersion;
        }

        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        if (current == mirror$activePipeline) return;

        if (mirror$activePipeline != null) {
            mirror$programsByPipeline.put(mirror$activePipeline,
                    new OculusTerrainProgramState(programs, shadersCreated));
        }

        OculusTerrainProgramState selected = mirror$programsByPipeline.remove(current);
        if (selected == null) {
            programs = new EnumMap<>(IrisTerrainPass.class);
            shadersCreated = false;
        } else {
            programs = selected.programs();
            shadersCreated = selected.shadersCreated();
        }
        versionCounterForSodiumShaderReload = globalVersion;
        mirror$activePipeline = current;
    }

    @Inject(method = "deleteShaders", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$deleteInactivePrograms(CallbackInfo callback) {
        mirror$programsByPipeline.values().forEach(state -> mirror$deletePrograms(state.programs()));
        mirror$programsByPipeline.clear();
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
