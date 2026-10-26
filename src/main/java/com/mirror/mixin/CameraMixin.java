package com.mirror.mixin;

import com.mirror.client.MirrorLevelRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
abstract class CameraMixin {
    // The reflection camera is set up detached so renderers treat it as a third-person view, but
    // its position is assigned right after setup. Skip the third-person zoom ray casts.
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void mirror$skipCaptureCameraZoom(double startingDistance, CallbackInfoReturnable<Double> callback) {
        if (MirrorLevelRenderer.isCaptureCamera((Camera) (Object) this)) callback.setReturnValue(0.0D);
    }
}
