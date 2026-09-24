package com.mirror.client;

import com.mirror.common.MirrorBlockEntity;
import com.mirror.common.ScreenRect;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.UUID;

/** One cached off-screen view. It is refreshed outside BlockEntityRenderer iteration. */
public final class MirrorReflectionTexture implements AutoCloseable {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final ResourceLocation UNDERLAY =
            new ResourceLocation("mirror", "textures/block/mirror/underlay.png");
    private static final ResourceLocation OVERLAY =
            new ResourceLocation("mirror", "textures/block/mirror/overlay.png");

    private final long viewId;
    private final ResourceLocation textureLocation;
    private TextureTarget surfaceTarget;
    private final MirrorRenderTargetTexture surfaceTexture;
    private final MirrorLevelRendererHooks.TextureState cullingState =
            new MirrorLevelRendererHooks.TextureState();
    private final MirrorProjectionStabilizer projectionStabilizer = new MirrorProjectionStabilizer();
    private final MirrorViewHistory viewHistory = new MirrorViewHistory();
    private final int recursionDepth;
    private final List<UUID> parentChain;
    private final MirrorCaptureSizing sizing = new MirrorCaptureSizing();
    private boolean rendered;
    private long firstRenderNanos = -1L;

    public MirrorReflectionTexture(int recursionDepth, List<UUID> parentChain) {
        viewId = NEXT_ID.getAndIncrement();
        textureLocation = new ResourceLocation("mirror", "reflection/" + viewId);
        surfaceTexture = new MirrorRenderTargetTexture(null);
        this.recursionDepth = recursionDepth;
        this.parentChain = List.copyOf(parentChain);
        Minecraft.getInstance().getTextureManager().register(textureLocation, surfaceTexture);
        MirrorDiagnostics.recordResource("VIEW_CREATE", viewId, 0, 0);
    }

    void requestSize(long frame, double pixels, double aspect, double density) {
        sizing.request(frame, pixels, aspect, density, MirrorCapturePool.maximumTextureSize());
    }

    static boolean canReuseCapacity(int capacityWidth, int capacityHeight,
                                    int requestedWidth, int requestedHeight) {
        return requestedWidth <= capacityWidth && requestedHeight <= capacityHeight;
    }

    public ResourceLocation textureLocation() {
        return textureLocation;
    }

    public boolean hasRendered() {
        return rendered;
    }

    public float fade() {
        if (firstRenderNanos < 0) return 0.0f;
        long elapsed = System.nanoTime() - firstRenderNanos;
        return Math.min(1.0f, elapsed / 300_000_000.0f);
    }

    public void render(Level level, MirrorBlockEntity mirror, Vec3 eye, float partialTick,
                       List<MirrorLevelRenderer.ReflectionPlane> parentPath) {
        if (mirror.isRemoved() || level != Minecraft.getInstance().level || Minecraft.getInstance().player == null) {
            MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.REMOVED);
            return;
        }
        long passStart = MirrorDiagnostics.startTimer();
        int captureWidth = 0;
        int captureHeight = 0;
        boolean completed = false;
        try {
            ScreenRect screen = mirror.getScreenRect();
            MirrorReflection groupReflection = MirrorReflection.compute(screen.center(), screen.normal(), eye);
            if (!groupReflection.viewerInFront()) {
                MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.BACKFACE);
                return;
            }
            MirrorProjection projection = MirrorProjection.forMirror(screen, groupReflection,
                    MirrorBlockEntity.FRAME_PIXELS / 32.0);
            int oldWidth = sizing.width();
            int oldHeight = sizing.height();
            boolean shaderCapture = OculusCompat.isShaderPackInUse();
            sizing.update(MirrorCapturePool.maximumCaptureSize(shaderCapture), shaderCapture);
            if (oldWidth != sizing.width() || oldHeight != sizing.height()) {
                viewHistory.reset();
                MirrorDiagnostics.recordResource("SIZE_CHANGE", viewId, sizing.width(), sizing.height());
            }
            MirrorCapturePool.CaptureSlot capture = MirrorCapturePool.acquire(
                    recursionDepth, sizing.width(), sizing.height());
            captureWidth = capture.target().width;
            captureHeight = capture.target().height;
            MirrorProjection.ViewportProjection captureProjection = OculusCompat.isShaderPackInUse()
                    ? projectionStabilizer.fit(projection, captureWidth, captureHeight)
                    : projection.offAxis();
            MirrorLevelRenderer.render(level, mirror, groupReflection, capture.target(), partialTick,
                    captureProjection, recursionDepth, parentChain, parentPath,
                    cullingState, viewId, viewHistory);
            long composeStart = MirrorDiagnostics.startTimer();
            TextureTarget destination = surfaceTarget;
            if (destination == null || !canReuseCapacity(destination.width, destination.height,
                    sizing.width(), sizing.height())) {
                destination = new TextureTarget(
                        Math.max(sizing.width(), surfaceTarget == null ? 0 : surfaceTarget.width),
                        Math.max(sizing.height(), surfaceTarget == null ? 0 : surfaceTarget.height), false, false);
                MirrorDiagnostics.recordResource("SURFACE_ALLOC", viewId, destination.width, destination.height);
            }
            try {
                compose(mirror, capture.target(), captureProjection.crop(), destination);
                if (destination != surfaceTarget) {
                    TextureTarget previousSurface = surfaceTarget;
                    surfaceTarget = destination;
                    surfaceTexture.setTarget(destination);
                    if (previousSurface != null) MirrorTextureManager.retireSurface(previousSurface);
                }
            } finally {
                if (destination != surfaceTarget) destination.destroyBuffers();
                MirrorDiagnostics.finishTimer(MirrorDiagnostics.Stage.COMPOSE, composeStart);
            }
            surfaceTexture.refreshId();
            rendered = true;
            completed = true;
            if (firstRenderNanos < 0) firstRenderNanos = System.nanoTime();
        } catch (MirrorPipelineUnavailableException unavailable) {
            MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.PIPELINE);
        } finally {
            MirrorDiagnostics.finishTimer(MirrorDiagnostics.Stage.PASS_TOTAL, passStart);
            MirrorDiagnostics.recordCapture(viewId, mirror.getId(), recursionDepth,
                    captureWidth, captureHeight, completed, passStart);
        }
    }

    private void compose(MirrorBlockEntity mirror, TextureTarget captureTarget,
                         MirrorProjection.UvRect reflectionCrop, TextureTarget destination) {
        Minecraft minecraft = Minecraft.getInstance();
        ShaderInstance shader = MirrorClient.MIRROR_COMPOSITE_SHADER;
        if (shader == null) {
            throw new IllegalStateException("Mirror composition shader is unavailable");
        }
        AbstractTexture underlay = minecraft.getTextureManager().getTexture(UNDERLAY);
        AbstractTexture overlay = minecraft.getTextureManager().getTexture(OVERLAY);
        com.mojang.blaze3d.pipeline.RenderTarget mainTarget = minecraft.getMainRenderTarget();
        boolean applied = false;
        MirrorRenderState.ScissorState outerScissor = MirrorRenderState.captureScissorState();
        destination.bindWrite(true);
        try {
            RenderSystem.viewport(0, 0, destination.width, destination.height);
            // Composition must cover the complete mirror surface even when the shader pipeline
            // that just finished left a partial scissor rectangle active.
            RenderSystem.disableScissor();
            RenderSystem.clear(16384, true);
            shader.setSampler("Sampler0", captureTarget);
            shader.setSampler("Sampler1", underlay);
            shader.setSampler("Sampler2", overlay);
            shader.safeGetUniform("ColorModulator").set(1.0f, 1.0f, 1.0f, 1.0f);
            shader.safeGetUniform("FogStart").set(0.0f);
            shader.safeGetUniform("FogEnd").set(1.0f);
            shader.safeGetUniform("FogColor").set(0.0f, 0.0f, 0.0f, 0.0f);
            shader.safeGetUniform("Tiles").set((float) mirror.getConnectedWidth(),
                    (float) mirror.getConnectedHeight());
            shader.safeGetUniform("Fade").set(fade());
            shader.safeGetUniform("ReflectionUvRect").set(
                    reflectionCrop.minU(), reflectionCrop.minV(),
                    reflectionCrop.maxU(), reflectionCrop.maxV());
            shader.apply();
            applied = true;
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();

            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            compositionVertex(builder, -1.0f, -1.0f, 0.0f, 0.0f);
            compositionVertex(builder, 1.0f, -1.0f, 1.0f, 0.0f);
            compositionVertex(builder, 1.0f, 1.0f, 1.0f, 1.0f);
            compositionVertex(builder, -1.0f, 1.0f, 0.0f, 1.0f);
            BufferUploader.draw(builder.end());
        } finally {
            if (applied) shader.clear();
            destination.unbindWrite();
            // Normalize the target and basic draw state for another reflection in this batch. The
            // processPending transaction owns restoration of the caller's exact GL/RenderSystem state
            // after every queued world render and composition has completed.
            mainTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
            outerScissor.restore();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
        }
    }

    private static void compositionVertex(BufferBuilder builder, float x, float y, float u, float v) {
        builder.vertex(x, y, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(0)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
    }

    @Override
    public void close() {
        EmbeddiumCompat.releaseMirrorView(viewId);
        com.mirror.compat.MirrorViewResources.release(viewId);
        OculusCompat.releaseMirrorView(viewId);
        Minecraft.getInstance().getTextureManager().release(textureLocation);
        cullingState.clear();
        projectionStabilizer.reset();
        viewHistory.reset();
        if (surfaceTarget != null) {
            MirrorDiagnostics.recordResource("SURFACE_FREE", viewId, surfaceTarget.width, surfaceTarget.height);
            surfaceTarget.destroyBuffers();
        }
        MirrorDiagnostics.recordResource("VIEW_FREE", viewId, 0, 0);
    }

}
