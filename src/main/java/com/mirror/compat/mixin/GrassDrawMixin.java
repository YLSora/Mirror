package com.mirror.compat.mixin;

import com.mirror.compat.ThirdPartyFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.leonardoinc22.shortgrass.client.render.GrassDrawDispatcher", remap = false)
abstract class GrassDrawMixin {
    @Unique private static long mirror$bakeFrame = Long.MIN_VALUE;

    // Static baking scans the entire shared mesh cache; animated dispatch and uniforms remain per view.
    @Inject(method = "bakeStaticSections", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$sharedBakeBudget(CallbackInfo ci) {
        if (!ThirdPartyFrame.primary() || mirror$bakeFrame == ThirdPartyFrame.id()) ci.cancel();
        else {
            mirror$bakeFrame = ThirdPartyFrame.id();
            ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_STATIC_BAKE);
        }
    }
}
