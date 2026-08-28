package com.mirror.mixin;

import com.mirror.client.MirrorPassContext;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntSupplier;

/** Keeps every nested mirror capture on the same real-frame counter as the outer world pass. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$FrameCounter", remap = false)
abstract class OculusFrameCounterMixin {
    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$keepOuterFrameCounter(CallbackInfo callback) {
        if (MirrorPassContext.isActive()) callback.cancel();
    }
}
