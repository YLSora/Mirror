package com.mirror.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mirror.client.MirrorPassContext;
import com.mirror.compat.ThirdPartyFrame;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BooleanSupplier;

@Mixin(value = AbstractRenderPipeline.class, remap = false)
abstract class VoxyPipelineMaintenanceMixin {
    @Unique private long mirror$nodeFrame = Long.MIN_VALUE;
    @Unique private long mirror$cleanFrame = Long.MIN_VALUE;
    @Unique private long mirror$downloadFrame = Long.MIN_VALUE;

    @WrapOperation(method = "innerPrimaryWork", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager;tick(Lme/cortex/voxy/client/core/gl/GlBuffer;Lme/cortex/voxy/client/core/rendering/hierachical/NodeCleaner;)V"), require = 1)
    private void mirror$nodes(AsyncNodeManager nodes, GlBuffer buffer, NodeCleaner cleaner, Operation<Void> original) {
        if (MirrorPassContext.isActive()) return;
        if (mirror$nodeFrame == ThirdPartyFrame.id() && !VoxyClient.isFrexActive()) return;
        mirror$nodeFrame = ThirdPartyFrame.id();
        ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_NODES);
        original.call(nodes, buffer, cleaner);
    }

    @WrapOperation(method = "innerPrimaryWork", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/hierachical/NodeCleaner;tick(Lme/cortex/voxy/client/core/gl/GlBuffer;)V"), require = 1)
    private void mirror$cleaner(NodeCleaner cleaner, GlBuffer buffer, Operation<Void> original) {
        if (MirrorPassContext.isActive()) return;
        if (mirror$cleanFrame == ThirdPartyFrame.id() && !VoxyClient.isFrexActive()) return;
        mirror$cleanFrame = ThirdPartyFrame.id();
        ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_CLEAN);
        original.call(cleaner, buffer);
    }

    @WrapOperation(method = "innerPrimaryWork", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/util/DownloadStream;tick()V"), require = 1)
    private void mirror$downloads(DownloadStream stream, Operation<Void> original) {
        if (MirrorPassContext.isActive()) return;
        if (mirror$downloadFrame == ThirdPartyFrame.id() && !VoxyClient.isFrexActive()) return;
        mirror$downloadFrame = ThirdPartyFrame.id();
        ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_DOWNLOAD);
        original.call(stream);
    }

    @WrapOperation(method = "innerPrimaryWork", at = @At(value = "INVOKE",
            target = "Ljava/util/function/BooleanSupplier;getAsBoolean()Z"), require = 1)
    private boolean mirror$noReflectionGlobalDrain(BooleanSupplier work, Operation<Boolean> original) {
        return !MirrorPassContext.isActive() && original.call(work);
    }
}
