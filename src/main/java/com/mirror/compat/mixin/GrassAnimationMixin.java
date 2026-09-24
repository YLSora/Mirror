package com.mirror.compat.mixin;

import com.mirror.compat.ThirdPartyFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.leonardoinc22.shortgrass.client.render.GrassShaderUniforms", remap = false)
abstract class GrassAnimationMixin {
    @Unique private static long mirror$animationFrame = Long.MIN_VALUE;
    @Unique private static float mirror$animationTime;

    @Inject(method = "advanceRenderAnimationTicks", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$sharedTime(CallbackInfoReturnable<Float> ci) {
        if (mirror$animationFrame == ThirdPartyFrame.id()) ci.setReturnValue(mirror$animationTime);
    }

    @Inject(method = "advanceRenderAnimationTicks", at = @At("RETURN"), require = 1)
    private static void mirror$rememberTime(CallbackInfoReturnable<Float> ci) {
        mirror$animationFrame = ThirdPartyFrame.id();
        mirror$animationTime = ci.getReturnValueF();
    }
}
