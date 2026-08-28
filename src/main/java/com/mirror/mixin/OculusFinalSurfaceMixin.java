package com.mirror.mixin;

import com.mirror.client.DeferredMirrorSurfaceRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws completed mirror surfaces after whichever Iris pipeline just finished color mapping. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class OculusFinalSurfaceMixin {
    @Inject(method = "finalizeLevelRendering", at = @At("TAIL"), require = 1, remap = false)
    private void mirror$presentFinalSurfaces(CallbackInfo callback) {
        DeferredMirrorSurfaceRenderer.flushCurrentPass();
    }
}
