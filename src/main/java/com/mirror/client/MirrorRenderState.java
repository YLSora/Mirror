package com.mirror.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;

import java.nio.IntBuffer;

/** Render-thread snapshot for the small set of global states touched by an off-screen pass. */
final class MirrorRenderState {
    private final Matrix4f projection;
    private final Matrix4f modelView;
    private final Matrix3f normal;
    private final Matrix3f inverseViewRotation;
    private final int[] viewport;
    private final int[] textures;
    private final boolean depthTest;
    private final boolean blend;
    private final boolean cull;
    private final boolean polygonOffset;

    private MirrorRenderState() {
        projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        PoseStack.Pose pose = RenderSystem.getModelViewStack().last();
        normal = new Matrix3f(pose.normal());
        inverseViewRotation = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());

        IntBuffer viewportBuffer = BufferUtils.createIntBuffer(4);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewportBuffer);
        viewport = new int[]{viewportBuffer.get(0), viewportBuffer.get(1),
                viewportBuffer.get(2), viewportBuffer.get(3)};
        textures = new int[]{RenderSystem.getShaderTexture(0), RenderSystem.getShaderTexture(1),
                RenderSystem.getShaderTexture(2), RenderSystem.getShaderTexture(3)};
        depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        polygonOffset = GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL);
    }

    static MirrorRenderState capture() {
        return new MirrorRenderState();
    }

    void restore() {
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        RenderSystem.setProjectionMatrix(projection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        PoseStack.Pose pose = RenderSystem.getModelViewStack().last();
        pose.pose().set(modelView);
        pose.normal().set(normal);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setInverseViewRotationMatrix(inverseViewRotation);
        for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, textures[i]);
        setState(GL11C.GL_DEPTH_TEST, depthTest, RenderSystem::enableDepthTest, RenderSystem::disableDepthTest);
        setState(GL11C.GL_BLEND, blend, RenderSystem::enableBlend, RenderSystem::disableBlend);
        setState(GL11C.GL_CULL_FACE, cull, RenderSystem::enableCull, RenderSystem::disableCull);
        setState(GL11C.GL_POLYGON_OFFSET_FILL, polygonOffset,
                RenderSystem::enablePolygonOffset, RenderSystem::disablePolygonOffset);
    }

    private static void setState(int capability, boolean enabled, Runnable enable, Runnable disable) {
        if (GL11C.glIsEnabled(capability) != enabled) {
            if (enabled) enable.run();
            else disable.run();
        }
    }
}
