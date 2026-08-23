package com.mirror.client;

import com.mirror.common.MirrorBlock;
import com.mirror.common.MirrorBlockEntity;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
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

    private final ResourceLocation textureLocation;
    private final TextureTarget target;
    private final MirrorRenderTargetTexture texture;
    private final int recursionDepth;
    private final List<UUID> parentChain;
    private boolean rendered;
    private long firstRenderNanos = -1L;

    public MirrorReflectionTexture(int width, int height, int recursionDepth, List<UUID> parentChain) {
        textureLocation = new ResourceLocation("mirror", "reflection/" + NEXT_ID.getAndIncrement());
        target = new TextureTarget(width, height, true, false);
        texture = new MirrorRenderTargetTexture(target);
        this.recursionDepth = recursionDepth;
        this.parentChain = List.copyOf(parentChain);
        Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
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

        MirrorLevelRenderer.render(level, mirror, groupReflection, target, partialTick,
                projection, facing.toYRot(), 0.0f, recursionDepth, parentChain);
        texture.refreshId();
        rendered = true;
        if (firstRenderNanos < 0) firstRenderNanos = System.nanoTime();
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(textureLocation);
        target.destroyBuffers();
    }

}
