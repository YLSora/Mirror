package com.mirror.compat.mixin;

import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPassContext;
import com.mirror.compat.MirrorViewResources;
import com.mirror.compat.ThirdPartyFrame;
import com.mirror.compat.VoxyViewKey;
import com.mojang.logging.LogUtils;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(value = ViewportSelector.class, remap = false)
abstract class VoxyViewportMixin implements MirrorViewResources.Owner {
    @Shadow @Final private Map<Object, Viewport<?>> extraViewports;
    @Shadow protected abstract Viewport<?> getOrCreate(Object holder);
    @Unique private final Long2LongOpenHashMap mirror$epochs = new Long2LongOpenHashMap();
    @Unique private final Long2LongOpenHashMap mirror$frames = new Long2LongOpenHashMap();

    @Inject(method = "getViewport", at = @At("HEAD"), cancellable = true, require = 1)
    private void mirror$selectViewport(CallbackInfoReturnable<Viewport<?>> ci) {
        if (!MirrorPassContext.isActive() || ThirdPartyFrame.auxiliary()) return;
        MirrorViewResources.register(this);
        VoxyViewKey key = new VoxyViewKey(MirrorPassContext.current().viewId());
        boolean created = !extraViewports.containsKey(key);
        Viewport<?> viewport = getOrCreate(key);
        long epoch = ThirdPartyFrame.voxyGeometryEpoch();
        long frame = ThirdPartyFrame.id();
        if (created || mirror$epochs.get(key.view()) != epoch || mirror$frames.get(key.view()) < frame - 1) {
            // Main-view uploads may recycle geometry addresses referenced by a dormant view.
            MDICViewport mdic = (MDICViewport) viewport;
            mdic.drawCountCallBuffer.zero();
            mdic.visibilityBuffer.fill(-1);
            ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_HISTORY_RESET);
        }
        mirror$epochs.put(key.view(), epoch);
        mirror$frames.put(key.view(), frame);
        ci.setReturnValue(viewport);
        if (created) ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_VIEW_CREATE);
    }

    @Override public void releaseMirrorView(long view) {
        mirror$epochs.remove(view);
        mirror$frames.remove(view);
        Viewport<?> viewport = extraViewports.remove(new VoxyViewKey(view));
        if (viewport != null) {
            viewport.delete();
            ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_VIEW_FREE);
        }
    }

    @Override public void clearMirrorViews() {
        mirror$epochs.clear();
        mirror$frames.clear();
        var iterator = extraViewports.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!(entry.getKey() instanceof VoxyViewKey)) continue;
            entry.getValue().delete();
            iterator.remove();
            ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_VIEW_FREE);
        }
    }

    @Override public void reportMemory() {
        if (!MirrorDiagnostics.isDebugEnabled() || ThirdPartyFrame.id() % 120 != 0) return;
        long count = extraViewports.keySet().stream().filter(VoxyViewKey.class::isInstance).count();
        LogUtils.getLogger().info("[Mirror Voxy] mirrorViews={}, voxyBufferCapacityBytes={}, voxyEstimatedTextureBytes={}",
                count, GlBuffer.getTotalSize(), GlTexture.getEstimatedTotalSize());
    }

    @Inject(method = "free", at = @At("HEAD"), require = 1)
    private void mirror$unregister(CallbackInfo ci) { MirrorViewResources.unregister(this); }

}
