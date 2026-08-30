package com.mirror.mixin;

import com.mirror.client.MirrorLevelRenderer;
import com.mirror.client.MirrorTextureManager;
import com.mirror.client.OculusCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
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

    /**
     * Consume last frame's mirror requests after the outer LevelRenderer has returned but before
     * GameRenderer starts the first-person hand pass. MirrorLevelRenderer can therefore render the
     * full entity pipeline (including PlayerRenderer/YSM) without re-entering an active world pass,
     * while vanilla hand and GUI rendering still establish their own normal item-dependent state
     * after all off-screen work is complete.
     *
     * The renderHand field is read exactly once at this boundary in 1.20.1. Inject before the read so
     * reflection updates also run when hand rendering is disabled (third person, panorama, etc.).
     */
    @Inject(method = "renderLevel", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderHand:Z",
            opcode = Opcodes.GETFIELD, ordinal = 0))
    private void mirror$renderPendingReflections(float partialTick, long finishTimeNano,
                                                  PoseStack renderPose, CallbackInfo callback) {
        if (MirrorLevelRenderer.isRenderingReflection() || OculusCompat.isShadowPass()) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        MirrorTextureManager.processPending(camera, partialTick);
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
