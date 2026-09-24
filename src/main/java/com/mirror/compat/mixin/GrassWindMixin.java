package com.mirror.compat.mixin;

import com.mirror.compat.ThirdPartyFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.leonardoinc22.shortgrass.client.render.GrassWindParticles", remap = false)
abstract class GrassWindMixin {
    @Unique private static long mirror$updatedFrame = Long.MIN_VALUE;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$physicalWind(CallbackInfo ci) {
        if (!ThirdPartyFrame.primary() || mirror$updatedFrame == ThirdPartyFrame.id()) ci.cancel();
        else {
            mirror$updatedFrame = ThirdPartyFrame.id();
            ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_WIND);
        }
    }
}
