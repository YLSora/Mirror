package com.mirror.mixin;

import com.mirror.client.OculusCompat;
import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPassContext;
import com.mirror.client.MirrorPipelineBuildContext;
import com.mirror.client.MirrorPrimaryShadowView;
import com.mirror.client.MirrorShadowBorrowCapability;
import com.mirror.client.MirrorShadowRendererBuildCapability;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderBuffers;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents a reflected world render from starting a nested Oculus shadow render. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
abstract class OculusMirrorShadowPassMixin implements MirrorShadowRendererBuildCapability {
    @WrapOperation(method = "<init>", at = @At(value = "NEW",
            target = "net/minecraft/client/renderer/RenderBuffers"), require = 1)
    private RenderBuffers mirror$borrowShadowBuffers(Operation<RenderBuffers> original) {
        RenderBuffers buffers = MirrorPipelineBuildContext.borrowedShadowBuffers();
        return buffers == null ? original.call() : buffers;
    }

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$skipNestedShadowPass(LevelRendererAccessor levelRenderer, Camera camera,
                                              CallbackInfo callback) {
        boolean nested = OculusCompat.isMirrorPass();
        MirrorDiagnostics.recordShadowPass(nested);
        if (nested) callback.cancel();
    }

    @Inject(method = "renderShadows", at = @At(value = "FIELD",
            target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;MODELVIEW:Lorg/joml/Matrix4f;",
            opcode = Opcodes.PUTSTATIC, shift = At.Shift.AFTER, remap = false), require = 1, remap = false)
    private void mirror$recordPrimaryShadowView(LevelRendererAccessor levelRenderer, Camera camera,
                                                 CallbackInfo callback) {
        if (!MirrorPassContext.isActive() && ShadowRenderer.MODELVIEW != null) {
            WorldRenderingPipeline owner = Iris.getPipelineManager().getPipelineNullable();
            MirrorPrimaryShadowView.record(owner, ShadowRenderer.MODELVIEW,
                    CameraUniforms.getUnshiftedCameraPosition());
        }
    }

    @Inject(method = "createShadowModelView(FF)Lcom/mojang/blaze3d/vertex/PoseStack;", at = @At("HEAD"),
            cancellable = true, require = 1, remap = false)
    private static void mirror$usePrimaryShadowView(float sunPathRotation, float intervalSize,
                                                      CallbackInfoReturnable<PoseStack> callback) {
        if (!MirrorPassContext.isActive()) return;
        WorldRenderingPipeline selected = Iris.getPipelineManager().getPipelineNullable();
        Object owner = selected instanceof MirrorShadowBorrowCapability capability
                        && capability.mirror$usesBorrowedShadowTargets()
                ? capability.mirror$getPrimaryOwner() : null;
        Matrix4f modelView = owner == null ? null
                : MirrorPrimaryShadowView.forCamera(owner, CameraUniforms.getUnshiftedCameraPosition());
        if (modelView == null) return;
        PoseStack pose = new PoseStack();
        pose.last().pose().set(modelView);
        callback.setReturnValue(pose);
    }
}
