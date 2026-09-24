package com.mirror.mixin;

import com.mirror.client.MirrorPassContext;
import com.mirror.client.MirrorTemporalStateAccess;
import com.mirror.client.OculusMirrorHistory;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each reflected camera keeps its own persistent colortex pair within a shared shader slot. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class OculusMirrorTemporalStateMixin implements MirrorTemporalStateAccess {
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private PackDirectives packDirectives;
    @Unique private final OculusMirrorHistory mirror$history = new OculusMirrorHistory();

    @Inject(method = "beginLevelRendering", at = @At("HEAD"), require = 1)
    private void mirror$saveOutgoingHistory(CallbackInfo callback) {
        if (!MirrorPassContext.isActive()) return;
        mirror$history.leave(MirrorPassContext.current().viewId(), renderTargets,
                packDirectives.getRenderTargetDirectives().getRenderTargetSettings());
    }

    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/targets/RenderTargets;isFullClearRequired()Z"), require = 1)
    private void mirror$restoreIncomingHistory(CallbackInfo callback) {
        if (!MirrorPassContext.isActive()) return;
        mirror$history.enter(MirrorPassContext.current().viewId(), renderTargets,
                packDirectives.getRenderTargetDirectives().getRenderTargetSettings());
    }

    @Inject(method = "finalizeLevelRendering", at = @At("RETURN"), require = 1)
    private void mirror$commitHistory(CallbackInfo callback) {
        if (MirrorPassContext.isActive()) mirror$history.commit();
    }

    @Inject(method = "destroy", at = @At("HEAD"), require = 1)
    private void mirror$destroyHistory(CallbackInfo callback) { mirror$history.destroy(); }

    @Override public void mirror$releaseView(long viewId) { mirror$history.release(viewId); }
}
