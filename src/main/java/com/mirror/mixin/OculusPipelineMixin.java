package com.mirror.mixin;

import com.mirror.client.OculusCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional hooks are ignored completely when Oculus/Iris is absent. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class OculusPipelineMixin {
    @Inject(method = "beginLevelRendering", at = @At("HEAD"), remap = false, require = 0)
    private void mirror$beginLevelRendering(CallbackInfo callback) {
        if (OculusCompat.isLoaded()) OculusCompat.beginPipelineHook();
    }

    @Inject(method = "finalizeLevelRendering", at = @At("TAIL"), remap = false, require = 0)
    private void mirror$endLevelRendering(CallbackInfo callback) {
        if (OculusCompat.isLoaded()) OculusCompat.endPipelineHook();
    }
}
