package com.mirror.compat.mixin;

import com.mirror.client.MirrorPassContext;
import com.mirror.compat.MirrorViewResources;
import com.mirror.compat.ThirdPartyFrame;
import com.mirror.compat.VoxyDepthTarget;
import com.mirror.compat.VoxyColors;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NormalRenderPipeline.class, remap = false)
abstract class VoxyNormalTargetsMixin implements MirrorViewResources.Owner {
    @Shadow private GlTexture colourTex;
    @Shadow private GlTexture colourSSAOTex;
    @Shadow @Final private GlFramebuffer fbSSAO;
    @Unique private final Long2ObjectOpenHashMap<VoxyColors> mirror$colors = new Long2ObjectOpenHashMap<>();
    @Unique private VoxyColors mirror$mainColors;
    @Unique private long mirror$selected = -1L;

    @Unique private void mirror$saveColors() {
        if (colourTex == null) return;
        VoxyColors current = mirror$selected < 0 ? mirror$mainColors : mirror$colors.get(mirror$selected);
        if (current != null && current.color() == colourTex && current.ssao() == colourSSAOTex) return;
        VoxyColors colors = new VoxyColors(colourTex, colourSSAOTex);
        if (mirror$selected < 0) mirror$mainColors = colors;
        else mirror$colors.put(mirror$selected, colors);
    }

    @Unique private void mirror$select(long view) {
        if (mirror$selected == view) return;
        mirror$saveColors();
        VoxyColors colors = view < 0 ? mirror$mainColors : mirror$colors.get(view);
        colourTex = colors == null ? null : colors.color();
        colourSSAOTex = colors == null ? null : colors.ssao();
        mirror$selected = view;
        var depth = ((NormalRenderPipeline) (Object) this).fb;
        ((VoxyDepthTarget) depth).mirror$selectDepth(view);
        if (colors != null && depth.getDepthTex() != null) {
            depth.framebuffer.bind(36064, colourTex);
            fbSSAO.bind(depth.getDepthAttachmentType(), depth.getDepthTex()).bind(36064, colourSSAOTex);
        }
    }

    @Inject(method = "setup", at = @At("HEAD"), require = 1)
    private void mirror$selectTargets(Viewport<?> viewport, int source, int width, int height,
                                       CallbackInfoReturnable<Integer> ci) {
        long view = MirrorPassContext.isActive() && !ThirdPartyFrame.auxiliary()
                ? MirrorPassContext.current().viewId() : -1L;
        if (view >= 0) MirrorViewResources.register(this);
        mirror$select(view);
        if (colourTex == null || colourTex.getWidth() != viewport.width || colourTex.getHeight() != viewport.height) {
            ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_COLOR_ALLOC);
        }
    }

    @Override public void restoreMainView() { mirror$select(-1L); }

    @Override public void releaseMirrorView(long view) {
        if (mirror$selected == view) restoreMainView();
        VoxyColors colors = mirror$colors.remove(view);
        if (colors != null) colors.free();
    }

    @Override public void clearMirrorViews() {
        restoreMainView();
        mirror$colors.values().forEach(VoxyColors::free);
        mirror$colors.clear();
    }

    @Inject(method = "free", at = @At("HEAD"), require = 1)
    private void mirror$freeColors(CallbackInfo ci) {
        clearMirrorViews();
        MirrorViewResources.unregister(this);
    }

}
