package com.mirror.client;

import com.mirror.common.MirrorBlock;
import com.mirror.common.MirrorBlockEntity;
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
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

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

    private final ResourceLocation textureLocation;
    private final TextureTarget surfaceTarget;
    private final MirrorRenderTargetTexture surfaceTexture;
    private final MirrorLevelRendererHooks.TextureState cullingState =
            new MirrorLevelRendererHooks.TextureState();
    private final int recursionDepth;
    private final List<UUID> parentChain;
    private int layoutPixelWidth;
    private int layoutPixelHeight;
    private boolean rendered;
    private long firstRenderNanos = -1L;

    public MirrorReflectionTexture(int width, int height, int layoutPixelWidth, int layoutPixelHeight,
                                   int recursionDepth, List<UUID> parentChain) {
        textureLocation = new ResourceLocation("mirror", "reflection/" + NEXT_ID.getAndIncrement());
        surfaceTarget = new TextureTarget(width, height, false, false);
        surfaceTexture = new MirrorRenderTargetTexture(surfaceTarget);
        this.recursionDepth = recursionDepth;
        this.parentChain = List.copyOf(parentChain);
        this.layoutPixelWidth = layoutPixelWidth;
        this.layoutPixelHeight = layoutPixelHeight;
        Minecraft.getInstance().getTextureManager().register(textureLocation, surfaceTexture);
    }

    /**
     * A physically smaller connected mirror can keep the previous targets. This avoids compiling
     * a new shader pipeline while a block removal is being synchronized to the client.
     */
    boolean reuseForChangedLayout(int width, int height, int newLayoutPixelWidth, int newLayoutPixelHeight) {
        if (!canReuseForChangedLayout(surfaceTarget.width, surfaceTarget.height,
                layoutPixelWidth, layoutPixelHeight, width, height,
                newLayoutPixelWidth, newLayoutPixelHeight)) return false;
        layoutPixelWidth = newLayoutPixelWidth;
        layoutPixelHeight = newLayoutPixelHeight;
        return true;
    }

    static boolean canReuseForChangedLayout(int capacityWidth, int capacityHeight,
                                             int oldLayoutPixelWidth, int oldLayoutPixelHeight,
                                             int requestedWidth, int requestedHeight,
                                             int newLayoutPixelWidth, int newLayoutPixelHeight) {
        boolean layoutChanged = newLayoutPixelWidth != oldLayoutPixelWidth
                || newLayoutPixelHeight != oldLayoutPixelHeight;
        return layoutChanged && requestedWidth <= capacityWidth && requestedHeight <= capacityHeight;
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

    public void render(Level level, MirrorBlockEntity mirror, Vec3 eye, float partialTick) {
        if (mirror.isRemoved() || level != Minecraft.getInstance().level) return;
        Direction facing = mirror.getBlockState().getValue(MirrorBlock.FACING);
        Vec3 normal = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = normal.cross(up).normalize();

        double frame = MirrorBlockEntity.FRAME_PIXELS / 16.0;
        double halfWidth = (mirror.getConnectedWidth() - frame) * 0.5;
        double halfHeight = (mirror.getConnectedHeight() - frame) * 0.5;
        Vec3 masterPlane = Vec3.atCenterOf(mirror.getBlockPos())
                .add(normal.scale(0.5 - MirrorBlock.surfaceRecession(mirror.getBlockState())));
        Vec3 center = masterPlane
                .add(right.scale((1.0 - mirror.getConnectedWidth()) * 0.5))
                .add(up.scale((mirror.getConnectedHeight() - 1.0) * 0.5));
        MirrorReflection groupReflection = MirrorReflection.compute(center, normal, eye);
        double depth = groupReflection.signedDistance();
        if (!groupReflection.viewerInFront() || depth <= 0.0) {
            return;
        }

        float near = Math.max(0.05f, (float) depth);
        float scale = near / (float) depth;
        Vec3 bottomLeft = center.subtract(right.scale(halfWidth)).subtract(up.scale(halfHeight));
        Vec3 bottomRight = center.add(right.scale(halfWidth)).subtract(up.scale(halfHeight));
        Vec3 topLeft = center.subtract(right.scale(halfWidth)).add(up.scale(halfHeight));
        Vec3 reflectedEye = groupReflection.reflectedEye();
        float left = (float) bottomLeft.subtract(reflectedEye).dot(right) * scale;
        float rightPlane = (float) bottomRight.subtract(reflectedEye).dot(right) * scale;
        float bottom = (float) bottomLeft.subtract(reflectedEye).dot(up) * scale;
        float top = (float) topLeft.subtract(reflectedEye).dot(up) * scale;
        Matrix4f projection = new Matrix4f().frustum(left, rightPlane, bottom, top, near, 1000.0f);

        MirrorCapturePool.CaptureSlot capture = MirrorCapturePool.acquire(
                recursionDepth, surfaceTarget.width, surfaceTarget.height);
        MirrorLevelRenderer.render(level, mirror, groupReflection, capture.target(), partialTick,
                projection, facing.toYRot(), 0.0f, recursionDepth, parentChain, cullingState);
        compose(mirror, capture.target());
        surfaceTexture.refreshId();
        rendered = true;
        if (firstRenderNanos < 0) firstRenderNanos = System.nanoTime();
    }

    private void compose(MirrorBlockEntity mirror, TextureTarget captureTarget) {
        Minecraft minecraft = Minecraft.getInstance();
        ShaderInstance shader = MirrorClient.MIRROR_COMPOSITE_SHADER;
        if (shader == null) {
            throw new IllegalStateException("Mirror composition shader is unavailable");
        }
        AbstractTexture underlay = minecraft.getTextureManager().getTexture(UNDERLAY);
        AbstractTexture overlay = minecraft.getTextureManager().getTexture(OVERLAY);
        com.mojang.blaze3d.pipeline.RenderTarget mainTarget = minecraft.getMainRenderTarget();
        boolean applied = false;
        surfaceTarget.bindWrite(true);
        try {
            RenderSystem.viewport(0, 0, surfaceTarget.width, surfaceTarget.height);
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
            surfaceTarget.unbindWrite();
            // Composition runs only from RenderTick START, after the nested world transaction has
            // restored Oculus and before the next outer frame establishes its render state. Avoid
            // a second full GL snapshot here: it adds dozens of synchronous texture-state queries
            // per visible mirror and is particularly expensive with image-heavy shader packs.
            mainTarget.bindWrite(true);
            RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
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
        Minecraft.getInstance().getTextureManager().release(textureLocation);
        cullingState.clear();
        surfaceTarget.destroyBuffers();
    }

}
