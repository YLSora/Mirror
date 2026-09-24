package com.mirror.compat.mixin;

import com.mirror.compat.VoxyIrisBinding;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VoxyRenderSystem.class, remap = false)
abstract class VoxyIrisViewportMixin {
    @Shadow @Final private AbstractRenderPipeline pipeline;

    @Inject(method = "setupViewport", at = @At("HEAD"), require = 1)
    private void mirror$selectShaderOwner(CallbackInfoReturnable<?> callback) {
        if (pipeline instanceof VoxyIrisBinding binding) binding.mirror$selectIrisBindings();
    }
}
