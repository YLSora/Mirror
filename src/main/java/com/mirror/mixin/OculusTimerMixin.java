package com.mirror.mixin;

import com.mirror.client.OculusCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A nested mirror pass reuses the outer frame's frameTime values. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
abstract class OculusTimerMixin {
    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$keepOuterFrameTime(long frameStartTime, CallbackInfo callback) {
        if (OculusCompat.isMirrorPass()) callback.cancel();
    }
}
