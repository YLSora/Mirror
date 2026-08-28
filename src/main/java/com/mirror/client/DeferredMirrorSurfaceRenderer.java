package com.mirror.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Presents completed mirror surfaces after the active shader pipeline's final color transform. */
public final class DeferredMirrorSurfaceRenderer {
    private static final List<Surface> OUTER_SURFACES = new ArrayList<>();
    private static final Deque<List<Surface>> MIRROR_PASS_SURFACES = new ArrayDeque<>();

    private DeferredMirrorSurfaceRenderer() {
    }

    public static void beginFrame() {
        OUTER_SURFACES.clear();
        // A mirror pass is synchronous and must always close before the next outer render tick.
        // Recover instead of carrying stale surfaces across frames if a previous render aborted.
        MIRROR_PASS_SURFACES.clear();
    }

    /** Starts the presentation domain owned by one nested mirror world pass. */
    public static PassScope beginMirrorPass() {
        List<Surface> surfaces = new ArrayList<>();
        MIRROR_PASS_SURFACES.push(surfaces);
        return new PassScope(surfaces);
    }

    public static void submit(ResourceLocation texture, PoseStack.Pose pose,
                              float left, float right, float bottom, float top) {
        List<Surface> target = MIRROR_PASS_SURFACES.peek();
        if (target == null) target = OUTER_SURFACES;
        target.add(new Surface(texture, new Matrix4f(pose.pose()), left, right, bottom, top));
    }

    /**
     * Flushes the surface list belonging to the Iris pipeline that has just finished final color
     * mapping. During a mirror pass Minecraft#getMainRenderTarget is the capture target, so nested
     * mirror surfaces are written into the parent reflection only after its tone mapping has ended.
     */
    public static void flushCurrentPass() {
        List<Surface> surfaces = MIRROR_PASS_SURFACES.peek();
        if (surfaces == null) surfaces = OUTER_SURFACES;
        flush(surfaces);
    }


    private static void flush(List<Surface> surfaces) {
        if (surfaces.isEmpty()) return;

        Minecraft minecraft = Minecraft.getInstance();
        MirrorRenderState.ScissorState outerScissor = MirrorRenderState.captureScissorState();
        minecraft.getMainRenderTarget().bindWrite(false);
        RenderSystem.viewport(0, 0, minecraft.getMainRenderTarget().width,
                minecraft.getMainRenderTarget().height);
        // A final/composite shader is allowed to use a temporary scissor rectangle. Mirror
        // presentation happens after that pipeline and must cover every submitted surface.
        RenderSystem.disableScissor();
        for (Surface surface : surfaces) {
            // The reflection target is already fully lit. Present it with the dedicated unlit,
            // no-cull mirror surface state so orientation cannot change its brightness or visibility.
            RenderType renderType = MirrorRenderTypes.deferredMirrorSurface(surface.texture());
            renderType.setupRenderState();
            // The deferred render type targets MAIN_TARGET directly. Rebind defensively because
            // some shader packs leave a non-main FBO bound at the tail of finalizeLevelRendering.
            // During a nested pass MAIN_TARGET is intentionally the mirror capture target.
            minecraft.getMainRenderTarget().bindWrite(false);
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
            vertex(builder, surface, surface.left(), surface.bottom(), 0.0f, 0.0f);
            vertex(builder, surface, surface.right(), surface.bottom(), 1.0f, 0.0f);
            vertex(builder, surface, surface.right(), surface.top(), 1.0f, 1.0f);
            vertex(builder, surface, surface.left(), surface.top(), 0.0f, 1.0f);
            BufferUploader.drawWithShader(builder.end());
            renderType.clearRenderState();
        }
        surfaces.clear();
        minecraft.getMainRenderTarget().bindWrite(false);
        RenderSystem.viewport(0, 0, minecraft.getMainRenderTarget().width,
                minecraft.getMainRenderTarget().height);
        outerScissor.restore();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void vertex(BufferBuilder builder, Surface surface, float x, float y, float u, float v) {
        builder.vertex(surface.pose(), x, y, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .endVertex();
    }

    public static final class PassScope implements AutoCloseable {
        private final List<Surface> surfaces;
        private boolean closed;

        private PassScope(List<Surface> surfaces) {
            this.surfaces = surfaces;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            List<Surface> current = MIRROR_PASS_SURFACES.poll();
            if (current != surfaces) {
                // Never let diagnostic bookkeeping prevent the render transaction from restoring
                // the outer Oculus pipeline/FBO in its surrounding finally block.
                MIRROR_PASS_SURFACES.clear();
            }
            // If Iris never reached finalizeLevelRendering because the nested pass failed, discard
            // the incomplete surfaces rather than leaking them into a later pass/frame.
            surfaces.clear();
        }
    }

    private record Surface(ResourceLocation texture, Matrix4f pose,
                           float left, float right, float bottom, float top) {
    }
}
