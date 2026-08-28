package com.mirror.mixin;

import com.mirror.client.OculusCompat;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents a reflected world render from starting a nested Oculus shadow render. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
abstract class OculusMirrorShadowPassMixin {
    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$skipNestedShadowPass(LevelRendererAccessor levelRenderer, Camera camera,
                                              CallbackInfo callback) {
        if (OculusCompat.isMirrorPass()) callback.cancel();
    }
}
