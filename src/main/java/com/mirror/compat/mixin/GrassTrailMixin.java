package com.mirror.compat.mixin;

import com.mirror.compat.ThirdPartyFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.leonardoinc22.shortgrass.client.render.GrassTrailField", remap = false)
abstract class GrassTrailMixin {
    @Unique private static long mirror$updatedFrame = Long.MIN_VALUE;

    @Inject(method = "update", at = @At("HEAD"), cancellable = true, require = 1)
    private static void mirror$oncePerOuterFrame(CallbackInfo ci) {
        if (!ThirdPartyFrame.primary() || mirror$updatedFrame == ThirdPartyFrame.id()) {
            ci.cancel();
            return;
        }
        mirror$updatedFrame = ThirdPartyFrame.id();
        ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_TRAIL);
    }

    @Inject(method = "uploadTrailField", at = @At("HEAD"), require = 1)
    private static void mirror$countUpload(CallbackInfo ci) {
        ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_TRAIL_UPLOAD);
    }

    @Redirect(method = "reset", at = @At(value = "INVOKE",
            target = "Lcom/leonardoinc22/shortgrass/client/render/GrassTrailField;uploadTrailField()V"), require = 1)
    private static void mirror$deferResetUpload() {
        // reset already marks the entire field dirty; the next primary update uploads it once.
    }
}
