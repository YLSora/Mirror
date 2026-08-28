package com.mirror.client;

import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkShaderInterface;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisTerrainPass;

import java.util.EnumMap;

/**
 * Cached Embeddium terrain program set associated with one Oculus rendering pipeline.
 */
public record OculusTerrainProgramState(
        EnumMap<IrisTerrainPass, GlProgram<IrisChunkShaderInterface>> programs,
        boolean shadersCreated) {
}
