package com.mirror.mixin;

import com.mirror.client.OculusCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A nested mirror pass belongs to the current game frame and must not advance frameCounter. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$FrameCounter", remap = false)
abstract class OculusFrameCounterMixin {
    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$keepOuterFrameCounter(CallbackInfo callback) {
        if (OculusCompat.isMirrorPass()) callback.cancel();
    }
}
