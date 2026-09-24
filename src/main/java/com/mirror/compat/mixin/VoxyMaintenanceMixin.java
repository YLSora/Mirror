package com.mirror.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mirror.client.MirrorPassContext;
import com.mirror.compat.ThirdPartyFrame;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = VoxyRenderSystem.class, remap = false)
abstract class VoxyMaintenanceMixin {
    @Unique private long mirror$modelFrame = Long.MIN_VALUE;
    @Unique private long mirror$loadFrame = Long.MIN_VALUE;
    @Unique private long mirror$uploadFrame = Long.MIN_VALUE;
    @Unique private long mirror$paletteFrame = Long.MIN_VALUE;

    @WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/model/ModelBakerySubsystem;tick(J)V"), require = 1)
    private void mirror$modelBudget(ModelBakerySubsystem service, long budget, Operation<Void> original) {
        if (MirrorPassContext.isActive()) return;
        // FREX deliberately drains queues for offline captures; preserve its primary-view loop.
        if (mirror$modelFrame == ThirdPartyFrame.id() && !VoxyClient.isFrexActive()) return;
        mirror$modelFrame = ThirdPartyFrame.id();
        ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_MODEL);
        original.call(service, budget);
    }

    @WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/model/ModelBakerySubsystem;drainBlendPalette()V"), require = 1)
    private void mirror$palette(ModelBakerySubsystem service, Operation<Void> original) {
        if (MirrorPassContext.isActive() || mirror$paletteFrame == ThirdPartyFrame.id()) return;
        mirror$paletteFrame = ThirdPartyFrame.id();
        original.call(service);
    }

    @WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"), require = 1)
    private boolean mirror$physicalLoadCenter(RenderDistanceTracker tracker, double x, double z,
                                               Operation<Boolean> original) {
        if (MirrorPassContext.isActive()) return false;
        if (mirror$loadFrame == ThirdPartyFrame.id() && !VoxyClient.isFrexActive()) return false;
        mirror$loadFrame = ThirdPartyFrame.id();
        ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_LOAD);
        return original.call(tracker, x, z);
    }

    @WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/util/UploadStream;tick()V"), require = 1)
    private void mirror$uploadTick(UploadStream stream, Operation<Void> original) {
        if (MirrorPassContext.isActive() || mirror$uploadFrame == ThirdPartyFrame.id()) return;
        mirror$uploadFrame = ThirdPartyFrame.id();
        ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_UPLOAD);
        original.call(stream);
    }

    @WrapOperation(method = "renderOpaque", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/VoxyClient;isFrexActive()Z"), require = 2)
    private boolean mirror$noReflectionDrainLoop(Operation<Boolean> original) {
        return !MirrorPassContext.isActive() && original.call();
    }
}
