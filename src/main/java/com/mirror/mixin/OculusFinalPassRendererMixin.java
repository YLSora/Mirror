package com.mirror.mixin;

import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPassContext;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forces Oculus' final color framebuffer to follow the capture RenderTarget identity. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer", remap = false)
abstract class OculusFinalPassRendererMixin {
    @Shadow
    private int lastColorTextureVersion;

    @Unique
    private RenderTarget mirror$colorOwner;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, remap = false)
    private void mirror$rememberInitialColorOwner(CallbackInfo callback) {
        mirror$colorOwner = Minecraft.getInstance().getMainRenderTarget();
    }

    @Inject(method = "renderFinalPass", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$invalidateReplacedColorTarget(CallbackInfo callback) {
        if (!MirrorPassContext.isActive()) return;
        MirrorPassContext context = MirrorPassContext.current();
        RenderTarget target = context.captureTarget();
        if (mirror$colorOwner == target) return;

        // A deleted GL texture name can be recycled immediately. Oculus' ID/version check is
        // therefore insufficient when a pooled capture target is replaced at the same size.
        // Force its existing code path to call GlFramebuffer.addColorAttachment for this target.
        mirror$colorOwner = target;
        lastColorTextureVersion = Integer.MIN_VALUE;
        MirrorDiagnostics.recordResource("OCULUS_COLOR_REBIND", context.viewId(), target.width, target.height);
    }
}
