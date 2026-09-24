package com.mirror.mixin;

import com.mirror.client.EmbeddiumViewStateAccess;
import com.mirror.client.EmbeddiumSectionStateAccess;
import com.mirror.client.EmbeddiumViewSetupState;
import com.mirror.client.MirrorLevelRenderer;
import com.mirror.client.MirrorPassContext;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/** Binds mirror-only visibility for the full capture without repeating main-view preparation. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
abstract class EmbeddiumWorldRendererMixin implements EmbeddiumViewStateAccess {
    @Shadow private Viewport currentViewport;
    @Shadow private RenderSectionManager renderSectionManager;

    @Unique private final Deque<EmbeddiumViewSetupState> mirror$setups = new ArrayDeque<>();

    @Inject(method = "setupTerrain(Lnet/minecraft/client/Camera;Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;IZZ)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void mirror$beginSetup(Camera camera, Viewport viewport, int frame, boolean spectator,
                                   boolean updateImmediately, CallbackInfo callback) {
        if (!MirrorPassContext.isActive()) return;
        mirror$setups.push(new EmbeddiumViewSetupState(MirrorPassContext.current().viewId(),
                currentViewport, renderSectionManager));
        currentViewport = viewport;
        ((EmbeddiumSectionStateAccess) renderSectionManager).mirror$beginView(camera, viewport, frame, spectator);
        callback.cancel();
    }

    @Override
    public void mirror$finishCapture(long viewId) {
        while (!mirror$setups.isEmpty() && mirror$setups.peek().viewId() == viewId) {
            EmbeddiumViewSetupState state = mirror$setups.pop();
            try {
                ((EmbeddiumSectionStateAccess) state.manager()).mirror$endView();
            } finally {
                currentViewport = state.outerViewport();
            }
        }
    }

    @Override
    public void mirror$releaseView(long viewId) {
        if (renderSectionManager instanceof EmbeddiumSectionStateAccess access) {
            access.mirror$releaseView(viewId);
        }
    }
    @Redirect(method = "renderBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/RenderBuffers;"
            + "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;F"
            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD"
            + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V",
            at = @At(value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;"
                            + "isBoxVisible(Lnet/minecraft/world/phys/AABB;)Z"),
            require = 1, remap = false)
    private boolean mirror$useRecursiveSectionVisibility(Viewport viewport, AABB bounds) {
        if (MirrorLevelRenderer.isRecursivePass()) return true;
        return viewport.isBoxVisible(bounds);
    }

    @Redirect(method = "renderGlobalBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/RenderBuffers;"
            + "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;F"
            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD"
            + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V",
            at = @At(value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;"
                            + "isBoxVisible(Lnet/minecraft/world/phys/AABB;)Z"),
            require = 1, remap = false)
    private boolean mirror$useRecursiveGlobalSectionVisibility(Viewport viewport, AABB bounds) {
        if (MirrorLevelRenderer.isRecursivePass()) return true;
        return viewport.isBoxVisible(bounds);
    }

}
