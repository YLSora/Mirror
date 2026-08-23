package com.mirror.client;

import com.mirror.common.MirrorBlock;
import com.mirror.common.MirrorBlockEntity;
import com.mirror.config.MirrorConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
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
    public boolean shouldRenderOffScreen(MirrorBlockEntity entity) {
        return true;
    }

    public AABB getRenderBoundingBox(MirrorBlockEntity entity) {
        Direction facing = entity.getBlockState().getValue(MirrorBlock.FACING);
        int width = entity.getConnectedWidth();
        int height = entity.getConnectedHeight();
        AABB box = new AABB(entity.getBlockPos());
        return switch (facing) {
            case NORTH -> box.expandTowards(-width + 1, height - 1, 0);
            case SOUTH -> box.expandTowards(width - 1, height - 1, 0);
            case EAST -> box.expandTowards(0, height - 1, -width + 1);
            case WEST -> box.expandTowards(0, height - 1, width - 1);
            default -> box;
        };
    }

    @Override
    public void render(MirrorBlockEntity entity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        boolean renderingReflection = MirrorTextureManager.isRenderingReflection();
        if (renderingReflection) {
            MirrorConfig.RecursionMode mode = MirrorConfig.CLIENT.recursionMode.get();
            if (mode == MirrorConfig.RecursionMode.OFF || MirrorLevelRenderer.isMirrorInCurrentChain(entity)) return;
        }
        MirrorBlockEntity master = MirrorBlock.getMasterBlockEntity(entity.getLevel(), entity.getBlockPos());
        if (master == null || master.getBlockPos().equals(entity.getBlockPos()) == false) return;

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
        if (!reflection.viewerInFront()) return;

        MirrorReflectionTexture texture = !renderingReflection
                ? MirrorTextureManager.request(entity, eye, partialTick)
                : MirrorConfig.CLIENT.recursionMode.get() == MirrorConfig.RecursionMode.SHARED
                ? MirrorTextureManager.requestShared(entity, partialTick)
                : MirrorTextureManager.requestRecursive(entity, eye, partialTick);
        if (texture == null) return;
        drawFace(entity, facing, recession, poseStack, buffer, texture.textureLocation());
    }

    private static void drawFace(MirrorBlockEntity entity, Direction facing, double recession,
                                 PoseStack poseStack, MultiBufferSource buffer, net.minecraft.resources.ResourceLocation texture) {
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
        int sky = entity.getLevel().getBrightness(LightLayer.SKY, entity.getBlockPos().relative(facing));
        int light = LightTexture.pack(15, sky);
        VertexConsumer vertices = buffer.getBuffer(MirrorRenderTypes.material(texture,
                entity.getConnectedWidth(), entity.getConnectedHeight()));
        PoseStack.Pose pose = poseStack.last();
        // Render-target textures use OpenGL's bottom-left origin. The reflected camera already
        // supplies the mirror's horizontal reversal, so the surface must not flip either UV axis.
        quadVertex(vertices, pose, left, bottom, 0, 0, light);
        quadVertex(vertices, pose, right, bottom, 1, 0, light);
        quadVertex(vertices, pose, right, top, 1, 1, light);
        quadVertex(vertices, pose, left, top, 0, 1, light);
        poseStack.popPose();
    }

    private static void quadVertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y,
                                   float u, float v, int light) {
        vertices.vertex(pose.pose(), x, y, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();
    }
}
