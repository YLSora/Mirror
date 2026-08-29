package com.mirror.mixin;

import com.mirror.client.MirrorLevelRenderer;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Removes Embeddium's redundant physical block-entity cull from recursive mirror passes. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
abstract class EmbeddiumWorldRendererMixin {
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
