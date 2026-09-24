package com.mirror.mixin;

import com.mirror.client.OculusRenderTargetsAccess;
import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPassContext;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps Oculus depth attachments synchronized when a retained pipeline changes capture targets. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.targets.RenderTargets", remap = false)
abstract class OculusRenderTargetsMixin implements OculusRenderTargetsAccess {
    @Shadow
    private boolean fullClearRequired;

    @Shadow
    private int cachedDepthBufferVersion;

    @Shadow
    private int currentDepthTexture;

    @Unique
    private RenderTarget mirror$depthOwner;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, remap = false)
    private void mirror$rememberInitialDepthOwner(CallbackInfo callback) {
        mirror$depthOwner = Minecraft.getInstance().getMainRenderTarget();
    }

    @Inject(method = "resizeIfNeeded", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$invalidateReplacedDepthTarget(int depthBufferVersion, int depthTexture,
                                                     int width, int height, DepthBufferFormat format,
                                                     PackDirectives directives,
                                                     CallbackInfoReturnable<Boolean> callback) {
        if (!MirrorPassContext.isActive()) return;
        MirrorPassContext context = MirrorPassContext.current();
        RenderTarget target = context.captureTarget();
        if (mirror$depthOwner == target && currentDepthTexture == depthTexture) return;

        // Oculus versions are local to a RenderTarget, not globally unique. A new capture can
        // have the old version (and even a recycled GL name). Invalidate the version comparison
        // so Oculus updates its depth sampler and every owned depth attachment itself.
        cachedDepthBufferVersion = depthBufferVersion ^ Integer.MIN_VALUE;
        mirror$depthOwner = target;
        // A mirror depth owner switch only changes depth attachments. It must not invalidate
        // persistent colortex history for every other reflected camera in this pipeline slot.
        MirrorDiagnostics.recordResource("OCULUS_DEPTH_REBIND", context.viewId(), width, height);
    }

    @Override
    public void mirror$requestFullClear() {
        fullClearRequired = true;
    }
}
