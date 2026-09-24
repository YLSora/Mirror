package com.mirror.mixin;

import com.mirror.client.MirrorPassContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Updating camera uniforms per capture must not advance the shader's global TAA clock. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramUniforms", remap = false)
abstract class OculusPerViewUniformsMixin {
    @Shadow private int lastFrame;
    @Unique private MirrorPassContext mirror$lastCapture;

    @Inject(method = "update", at = @At("HEAD"), require = 1)
    private void mirror$invalidatePerFrameCacheOnViewChange(CallbackInfo callback) {
        MirrorPassContext capture = MirrorPassContext.isActive() ? MirrorPassContext.current() : null;
        if (mirror$lastCapture != capture) {
            lastFrame = Integer.MIN_VALUE;
            mirror$lastCapture = capture;
        }
    }
}
