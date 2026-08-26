package com.mirror.mixin;

import com.mirror.client.DeferredMirrorSurfaceRenderer;
import com.mirror.client.OculusCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws LDR mirror surfaces after the outer shader pipeline has finished color mapping. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class OculusFinalSurfaceMixin {
    @Inject(method = "finalizeLevelRendering", at = @At("TAIL"), require = 1, remap = false)
    private void mirror$presentFinalSurfaces(CallbackInfo callback) {
        if (!OculusCompat.isMirrorPass()) DeferredMirrorSurfaceRenderer.flush();
    }
}
