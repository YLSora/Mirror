package com.mirror.mixin;

import com.mirror.MirrorMod;
import com.mirror.client.MirrorLevelRenderer;
import com.mirror.client.MirrorLevelRendererHooks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "setupRender", at = @At("HEAD"), cancellable = true)
    private void mirror$setupReflectionRender(Camera camera, Frustum frustum,
                                               boolean hasCapturedFrustum, boolean isSpectator,
                                               CallbackInfo callback) {
        if (!MirrorLevelRenderer.isRenderingReflection()) return;
        MirrorLevelRendererHooks.applyFrustum((LevelRenderer) (Object) this, frustum);
        callback.cancel();
    }

    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void mirror$hideTaggedEntity(Entity entity, double x, double y, double z, float partialTick,
                                         PoseStack poseStack, MultiBufferSource buffer, CallbackInfo callback) {
        if (MirrorLevelRenderer.isRenderingReflection()
                && entity.getType().is(MirrorMod.CANT_SEE_THROUGH_MIRROR)) {
            callback.cancel();
        }
    }

    @Inject(method = "shouldShowEntityOutlines", at = @At("RETURN"), cancellable = true)
    private void mirror$disableReflectionOutlines(CallbackInfoReturnable<Boolean> callback) {
        if (MirrorLevelRenderer.isRenderingReflection()) callback.setReturnValue(false);
    }

    // The reflected pass has already selected loaded sections with its own camera and frustum.
    // The main ViewArea's compiled-section gate is therefore not authoritative for its entities.
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isChunkCompiled(" +
                    "Lnet/minecraft/core/BlockPos;)Z"))
    private boolean mirror$useReflectionChunkPass(LevelRenderer renderer, BlockPos blockPos) {
        if (MirrorLevelRenderer.isRenderingReflection()) return true;
        return renderer.isChunkCompiled(blockPos);
    }
}
