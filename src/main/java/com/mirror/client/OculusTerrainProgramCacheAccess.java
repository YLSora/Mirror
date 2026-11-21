package com.mirror.client;

import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkShaderInterface;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisTerrainPass;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Process-level lifecycle boundary for Oculus/Embeddium terrain programs. */
public interface OculusTerrainProgramCacheAccess {
    OculusTerrainProgramCacheStore<OculusTerrainProgramState> CACHE =
            new OculusTerrainProgramCacheStore<>(OculusTerrainProgramCacheAccess::deleteState);
    Set<OculusTerrainProgramCacheAccess> INSTANCES =
            Collections.newSetFromMap(new WeakHashMap<>());
    void mirror$discardMirrorPrograms(WorldRenderingPipeline pipeline);

    boolean mirror$prewarmPipeline(WorldRenderingPipeline pipeline);

    static void register(OculusTerrainProgramCacheAccess access) {
        INSTANCES.add(access);
    }

    static void destroyPipeline(WorldRenderingPipeline pipeline) {
        try {
            CACHE.remove(pipeline);
        } finally {
            for (OculusTerrainProgramCacheAccess access : Set.copyOf(INSTANCES)) {
                access.mirror$discardMirrorPrograms(pipeline);
            }
        }
    }

    static OculusTerrainProgramCacheStore.Lookup<OculusTerrainProgramState> take(
            WorldRenderingPipeline pipeline, Object vertexType) {
        return CACHE.take(pipeline, vertexType);
    }

    static void put(WorldRenderingPipeline pipeline, Object vertexType,
                    EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> programs,
                    boolean shadersCreated) {
        OculusTerrainProgramCacheStore.Lookup<OculusTerrainProgramState> previous =
                CACHE.take(pipeline, vertexType);
        if (previous.value() != null && previous.value().programs() == programs) {
            CACHE.put(pipeline, vertexType, previous.value());
            return;
        }
        if (previous.value() != null) deleteState(previous.value());
        CACHE.put(pipeline, vertexType, new OculusTerrainProgramState(programs, shadersCreated));
    }

    static void deleteState(OculusTerrainProgramState state) {
        Map<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> programs = state.programs();
        for (GlProgram<?> program : programs.values()) {
            if (program != null) program.delete();
        }
        programs.clear();
    }
}
