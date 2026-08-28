package com.mirror.mixin;

import com.mirror.MirrorMod;
import com.mirror.client.MirrorLevelRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
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

    // Vanilla has a second LocalPlayer-only gate in renderLevel: when the active camera entity
    // is not the LocalPlayer, that gate suppresses the local player even for a detached camera.
    // Reflection cameras intentionally use an unregistered dummy entity so dispatcher/camera
    // positions stay coherent. Redirect only this fourth Camera#getEntity call (ordinal 3 in
    // 1.20.1) so the LocalPlayer remains a normal world entity in every reflection depth.
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;getEntity()Lnet/minecraft/world/entity/Entity;",
            ordinal = 3))
    private Entity mirror$allowLocalPlayerInReflection(Camera camera) {
        if (MirrorLevelRenderer.isRenderingReflection()) {
            Entity player = Minecraft.getInstance().player;
            if (player != null) return player;
        }
        return camera.getEntity();
    }

    // EntityRenderer#shouldRender first applies the entity's normal distance limit to the
    // reflected camera position. After two plane reflections that virtual distance can be much
    // larger than the visible optical path, so the entity is rejected before the valid mirror
    // frustum is consulted. Nested passes already have their own decayed world render distance;
    // retain vanilla's bounding-box/frustum test and remove only that duplicate distance gate.
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(" +
                    "Lnet/minecraft/world/entity/Entity;" +
                    "Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean mirror$useRecursiveEntityFrustum(EntityRenderDispatcher dispatcher, Entity entity,
                                                      Frustum frustum, double cameraX,
                                                      double cameraY, double cameraZ) {
        if (!MirrorLevelRenderer.isRecursivePass()) {
            return dispatcher.shouldRender(entity, frustum, cameraX, cameraY, cameraZ);
        }

        AABB bounds = entity.getBoundingBoxForCulling().inflate(0.5D);
        if (bounds.hasNaN() || bounds.getSize() == 0.0D) {
            bounds = new AABB(entity.getX() - 2.0D, entity.getY() - 2.0D,
                    entity.getZ() - 2.0D, entity.getX() + 2.0D,
                    entity.getY() + 2.0D, entity.getZ() + 2.0D);
        }
        return entity.noCulling || frustum.isVisible(bounds);
    }
}
