package com.mirror.mixin;

import com.mirror.client.MirrorLevelRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Unique
    private PoseStack mirror$bobPose;

    @Unique
    private Matrix4f mirror$preBobPose;

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;F)V"), index = 0)
    private PoseStack mirror$captureBobBase(PoseStack renderPose) {
        mirror$bobPose = renderPose;
        mirror$preBobPose = new Matrix4f(renderPose.last().pose());
        return renderPose;
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;F)V", shift = At.Shift.AFTER))
    private void mirror$captureHurtBob(float partialTick, long finishTimeNano,
                                       PoseStack renderPose, CallbackInfo callback) {
        mirror$captureBobOffset();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(" +
                    "Lcom/mojang/blaze3d/vertex/PoseStack;F)V", shift = At.Shift.AFTER))
    private void mirror$captureWalkBob(float partialTick, long finishTimeNano,
                                       PoseStack renderPose, CallbackInfo callback) {
        mirror$captureBobOffset();
    }

    @Unique
    private void mirror$captureBobOffset() {
        if (mirror$bobPose == null || mirror$preBobPose == null) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Matrix4f pureBob = new Matrix4f(mirror$preBobPose).invert()
                .mul(new Matrix4f(mirror$bobPose.last().pose()));
        MirrorLevelRenderer.captureMainBobEyeOffset(camera, pureBob);
    }
}
