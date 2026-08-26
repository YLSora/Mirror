package com.mirror.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Presents completed mirror surfaces after a shader pack's final color transform. */
public final class DeferredMirrorSurfaceRenderer {
    private static final List<Surface> SURFACES = new ArrayList<>();

    private DeferredMirrorSurfaceRenderer() {
    }

    public static void beginFrame() {
        SURFACES.clear();
    }

    public static void submit(ResourceLocation texture, PoseStack.Pose pose,
                              float left, float right, float bottom, float top) {
        SURFACES.add(new Surface(texture, new Matrix4f(pose.pose()), new Matrix3f(pose.normal()),
                left, right, bottom, top));
    }

    public static void flush() {
        if (SURFACES.isEmpty() || OculusCompat.isMirrorPass()) return;

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getMainRenderTarget().bindWrite(false);
        RenderSystem.viewport(0, 0, minecraft.getMainRenderTarget().width,
                minecraft.getMainRenderTarget().height);
        for (Surface surface : SURFACES) {
            RenderType renderType = RenderType.entityTranslucentEmissive(surface.texture());
            renderType.setupRenderState();
            // Fabulous entity targets have already been resolved by the shader final pass. The
            // completed LDR surface belongs directly in the main target while retaining its depth.
            minecraft.getMainRenderTarget().bindWrite(false);
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            vertex(builder, surface, surface.left(), surface.bottom(), 0.0f, 0.0f);
            vertex(builder, surface, surface.right(), surface.bottom(), 1.0f, 0.0f);
            vertex(builder, surface, surface.right(), surface.top(), 1.0f, 1.0f);
            vertex(builder, surface, surface.left(), surface.top(), 0.0f, 1.0f);
            BufferUploader.drawWithShader(builder.end());
            renderType.clearRenderState();
        }
        SURFACES.clear();
        minecraft.getMainRenderTarget().bindWrite(false);
        RenderSystem.viewport(0, 0, minecraft.getMainRenderTarget().width,
                minecraft.getMainRenderTarget().height);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void vertex(BufferBuilder builder, Surface surface, float x, float y, float u, float v) {
        builder.vertex(surface.pose(), x, y, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(surface.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();
    }

    private record Surface(ResourceLocation texture, Matrix4f pose, Matrix3f normal,
                           float left, float right, float bottom, float top) {
    }
}
