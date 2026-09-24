package com.mirror.compat.mixin;

import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Comparator;
import java.util.List;

@Mixin(value = IrisVoxyRenderPipelineData.class, remap = false)
abstract class VoxyIrisUniformLayoutMixin {
    @Inject(method = "createUniformLayoutStructAndUpdater", at = @At("HEAD"), require = 1)
    private static void mirror$stableUniformOrder(List<?> uniforms,
            CallbackInfoReturnable<IrisVoxyRenderPipelineData.StructLayout> callback) {
        // CustomUniforms uses identity-hashed keys. Canonicalize before Voxy packs the UBO so
        // all Iris pipelines can use the same compiled Voxy programs with their own suppliers.
        uniforms.sort(Comparator.comparing(value -> ((VoxyIrisUniformNameAccess) value).mirror$uniformName()));
    }
}
