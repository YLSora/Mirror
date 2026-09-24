package com.mirror.compat.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mirror.client.MirrorPassContext;
import com.mirror.compat.MirrorViewResources;
import com.mirror.compat.ThirdPartyFrame;
import com.mirror.compat.VoxyDepthTarget;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import org.lwjgl.opengl.GL45C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = DepthFramebuffer.class, remap = false)
abstract class VoxyDepthMixin implements VoxyDepthTarget, MirrorViewResources.Owner {
    @Shadow private GlTexture depthBuffer;
    @Shadow @Final public GlFramebuffer framebuffer;
    @Shadow public abstract int getDepthAttachmentType();
    @Unique private final Long2ObjectOpenHashMap<GlTexture> mirror$depths = new Long2ObjectOpenHashMap<>();
    @Unique private GlTexture mirror$mainDepth;
    @Unique private long mirror$selected = -1L;

    @Override public void mirror$selectDepth(long view) {
        if (mirror$selected == view) return;
        mirror$saveDepth();
        depthBuffer = view < 0 ? mirror$mainDepth : mirror$depths.get(view);
        mirror$selected = view;
        GL45C.glNamedFramebufferTexture(framebuffer.id, getDepthAttachmentType(),
                depthBuffer == null ? 0 : depthBuffer.id, 0);
    }

    @Unique private void mirror$saveDepth() {
        if (mirror$selected < 0) mirror$mainDepth = depthBuffer;
        else if (depthBuffer != null) mirror$depths.put(mirror$selected, depthBuffer);
    }

    @WrapMethod(method = "resize")
    private boolean mirror$stableDepth(int width, int height, Operation<Boolean> original) {
        if (MirrorPassContext.isActive() && !ThirdPartyFrame.auxiliary()) {
            MirrorViewResources.register(this);
            mirror$selectDepth(MirrorPassContext.current().viewId());
        } else mirror$selectDepth(-1L);
        boolean allocated = original.call(width, height);
        mirror$saveDepth();
        if (allocated) ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_DEPTH_ALLOC);
        return allocated;
    }

    @Override public void restoreMainView() { mirror$selectDepth(-1L); }

    @Override public void releaseMirrorView(long view) {
        if (mirror$selected == view) restoreMainView();
        GlTexture texture = mirror$depths.remove(view);
        if (texture != null) texture.free();
    }

    @Override public void clearMirrorViews() {
        restoreMainView();
        mirror$depths.values().forEach(GlTexture::free);
        mirror$depths.clear();
    }

    @WrapMethod(method = "free")
    private void mirror$freeAllDepths(Operation<Void> original) {
        clearMirrorViews();
        MirrorViewResources.unregister(this);
        original.call();
        mirror$mainDepth = null;
        depthBuffer = null;
    }
}
