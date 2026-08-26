package com.mirror.mixin;

import com.mirror.client.OculusTerrainProgramCacheAccess;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkShaderInterface;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisTerrainPass;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.IdentityHashMap;
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
    private final Map<WorldRenderingPipeline, MirrorProgramState> mirror$programsByPipeline =
            new IdentityHashMap<>();

    @Unique
    private WorldRenderingPipeline mirror$activePipeline;

    @Unique
    private int mirror$knownGlobalVersion = Integer.MIN_VALUE;

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
                    new MirrorProgramState(programs, shadersCreated));
        }

        MirrorProgramState selected = mirror$programsByPipeline.remove(current);
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
    private static void mirror$deletePrograms(Map<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> values) {
        for (GlProgram<?> program : values.values()) {
            if (program != null) program.delete();
        }
        values.clear();
    }

    @Unique
    private record MirrorProgramState(
            EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> programs,
            boolean shadersCreated) {
    }
}
