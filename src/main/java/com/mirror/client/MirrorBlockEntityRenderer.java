package com.mirror.client;

import com.mirror.common.MirrorBlock;
import com.mirror.common.MirrorBlockEntity;
import com.mirror.config.MirrorConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public final class MirrorBlockEntityRenderer implements BlockEntityRenderer<MirrorBlockEntity> {
    private static final float INSET = 1.0f / 16.0f;

    public MirrorBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public int getViewDistance() {
        return MirrorConfig.CLIENT.renderDistance.get();
    }

    @Override
    public boolean shouldRender(MirrorBlockEntity entity, Vec3 cameraPos) {
        // A reflected camera is virtual and moves farther behind the physical mirrors at every
        // recursion step. Applying the player's fixed mirror visibility distance to that camera
        // silently stops submitting the opposite mirror after a few levels (typically depth 4),
        // before maxRecursionDepth can take effect. The active pass distance is expanded to cover
        // both the player's loaded-world radius and the virtual-camera displacement.
        double viewDistance = MirrorPassContext.isActive()
                ? MirrorPassContext.current().renderDistance()
                : getViewDistance();
        if (MirrorLevelRenderer.isRenderingReflection()) {
            MirrorDiagnostics.recordShouldRender(MirrorLevelRenderer.getChildDepth());
        }
        return entity.distanceToRenderBoundsSqr(cameraPos) <= viewDistance * viewDistance;
    }

    @Override
    public void render(MirrorBlockEntity entity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (FlashlightCompat.isAuxiliaryPass()) return;
        boolean renderingReflection = MirrorTextureManager.isRenderingReflection();
        if (OculusCompat.isShadowPass()) return;
        if (renderingReflection) {
            MirrorConfig.RecursionMode mode = MirrorConfig.CLIENT.recursionMode.get();
            if (mode == MirrorConfig.RecursionMode.OFF) return;
            MirrorDiagnostics.recordRendererEntry(MirrorLevelRenderer.getChildDepth());
        }
        MirrorBlockEntity master = MirrorBlock.getMasterBlockEntity(entity.getLevel(), entity.getBlockPos());
        if (master == null || master.getBlockPos().equals(entity.getBlockPos()) == false) return;
        // RECURSIVE mode must allow ancestor mirrors to appear again: a real two-mirror chain
        // alternates A -> B -> A -> B, with each step using a chain-isolated texture key. SHARED
        // mode still reuses direct textures, so keep its ancestor guard to avoid cyclic feedback.
        if (renderingReflection
                && MirrorConfig.CLIENT.recursionMode.get() == MirrorConfig.RecursionMode.SHARED
                && MirrorLevelRenderer.isParentMirror(master.getId())) return;

        Direction facing = entity.getBlockState().getValue(MirrorBlock.FACING);
        Vec3 normal = Vec3.atLowerCornerOf(facing.getNormal());
        double recession = MirrorBlock.surfaceRecession(entity.getBlockState());
        Vec3 planePoint = Vec3.atCenterOf(entity.getBlockPos()).add(normal.scale(0.5 - recession));
        Camera camera = renderingReflection ? MirrorLevelRenderer.getActiveCamera()
                : Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null) return;
        Vec3 eye = camera.getPosition();
        if (!renderingReflection) eye = eye.add(MirrorLevelRenderer.getMainBobEyeOffset());
        MirrorReflection reflection = MirrorReflection.compute(planePoint, normal, eye);
        if (!reflection.viewerInFront()) {
            if (renderingReflection) {
                MirrorDiagnostics.recordFacingRejected(MirrorLevelRenderer.getChildDepth());
            }
            return;
        }

        MirrorReflectionTexture texture = !renderingReflection
                ? MirrorTextureManager.request(entity)
                : MirrorConfig.CLIENT.recursionMode.get() == MirrorConfig.RecursionMode.SHARED
                ? MirrorTextureManager.requestShared(entity)
                : MirrorTextureManager.requestRecursive(entity);
        if (texture == null) return;
        drawFace(entity, facing, recession, poseStack, buffer, texture.textureLocation(),
                OculusCompat.shouldDeferSurfacePresentation()
                        || FlashlightCompat.shouldDeferSurfacePresentation());
    }

    private static void drawFace(MirrorBlockEntity entity, Direction facing, double recession,
                                 PoseStack poseStack, MultiBufferSource buffer,
                                 net.minecraft.resources.ResourceLocation texture, boolean defer) {
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - facing.toYRot()));
        poseStack.translate(0.0, 0.0, -0.5 + recession - 0.001);

        float width = entity.getConnectedWidth();
        float height = entity.getConnectedHeight();
        float left = 0.5f - width + INSET;
        float right = 0.5f - INSET;
        float bottom = -0.5f + INSET;
        float top = height - 0.5f - INSET;
        if (defer) {
            DeferredMirrorSurfaceRenderer.submit(texture, poseStack.last(), left, right, bottom, top);
            poseStack.popPose();
            return;
        }

        PoseStack.Pose pose = poseStack.last();
        RenderType renderType = MirrorRenderTypes.mirrorSurface(texture);
        VertexConsumer vertices = buffer.getBuffer(renderType);
        surfaceVertex(vertices, pose, left, bottom, 0, 0);
        surfaceVertex(vertices, pose, right, bottom, 1, 0);
        surfaceVertex(vertices, pose, right, top, 1, 1);
        surfaceVertex(vertices, pose, left, top, 0, 1);
        // Own this texture-specific batch so a later auxiliary renderer cannot flush it into a
        // different target through the shared global BufferSource.
        if (buffer instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(renderType);
        }
        poseStack.popPose();
    }

    private static void surfaceVertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y,
                                      float u, float v) {
        vertices.vertex(pose.pose(), x, y, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .endVertex();
    }

}
