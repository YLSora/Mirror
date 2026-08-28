package com.mirror.mixin;

import com.mirror.client.OculusShaderSourceCompatibility;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/** Applies the shared Oculus 1.8 GLSL compatibility patch to Vanilla/core shader output. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
abstract class OculusShaderSourceCompatibilityMixin {
    @Inject(method = "patchVanilla", at = @At("RETURN"), cancellable = true,
            require = 1, remap = false)
    private static void mirror$backportMissingVanillaShaderInterfaces(
            String name,
            String vertex,
            String geometry,
            String tessControl,
            String tessEval,
            String fragment,
            AlphaTest alpha,
            boolean isLines,
            boolean hasChunkOffset,
            ShaderAttributeInputs inputs,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            CallbackInfoReturnable<Map<PatchShaderType, String>> callback) {
        Map<PatchShaderType, String> original = callback.getReturnValue();
        if (original == null || original.isEmpty()) return;

        Map<PatchShaderType, String> patched = null;
        for (Map.Entry<PatchShaderType, String> entry : original.entrySet()) {
            String source = entry.getValue();
            if (source == null || source.isEmpty()) continue;
            String compatible = OculusShaderSourceCompatibility.patch(source);
            if (compatible.equals(source)) continue;
            if (patched == null) patched = new HashMap<>(original);
            patched.put(entry.getKey(), compatible);
        }
        if (patched != null) callback.setReturnValue(patched);
    }
}
