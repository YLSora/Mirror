package com.mirror.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import org.lwjgl.opengl.GL11C;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns the stable render target used by each mirror pipeline slot.
 *
 * <p>Mirror passes are serialized on the render thread, so all mirrors in the same recursion and
 * resolution slot can render through one target. Their finished images are copied immediately to
 * their individual surface targets.</p>
 */
public final class MirrorCapturePool {
    private static final int MIN_BUCKET_SIZE = 16;
    private static final int MIN_SHADER_BUCKET_SIZE = 256;
    private static final Map<MirrorPassContext.PipelineSlot, CaptureSlot> SLOTS = new HashMap<>();

    private MirrorCapturePool() {
    }

    public static CaptureSlot acquire(int recursionDepth, int requestedWidth, int requestedHeight) {
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalArgumentException("mirror capture dimensions must be positive");
        }
        int minimum = OculusCompat.isShaderPackInUse() ? MIN_SHADER_BUCKET_SIZE : MIN_BUCKET_SIZE;
        int side = bucketSide(requestedWidth, requestedHeight,
                GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE), minimum);
        MirrorPassContext.PipelineSlot key = new MirrorPassContext.PipelineSlot(
                recursionDepth, new MirrorPassContext.ResolutionBucket(side, side));
        return SLOTS.computeIfAbsent(key, ignored -> new CaptureSlot(key,
                new TextureTarget(side, side, true, false)));
    }

    static int bucketSide(int requestedWidth, int requestedHeight, int maximumTextureSize) {
        return bucketSide(requestedWidth, requestedHeight, maximumTextureSize, MIN_BUCKET_SIZE);
    }

    private static int bucketSide(int requestedWidth, int requestedHeight, int maximumTextureSize,
                                  int minimumBucketSize) {
        int minimum = Math.max(MIN_BUCKET_SIZE, minimumBucketSize);
        int required = Math.max(minimum, Math.max(requestedWidth, requestedHeight));
        int maximum = Math.max(MIN_BUCKET_SIZE, maximumTextureSize);
        if (required >= maximum) return maximum;

        int bucket = Integer.highestOneBit(required - 1) << 1;
        if (bucket <= 0) bucket = maximum;
        return Math.min(bucket, maximum);
    }

    public static void clear() {
        SLOTS.values().forEach(CaptureSlot::close);
        SLOTS.clear();
    }

    public record CaptureSlot(MirrorPassContext.PipelineSlot pipelineSlot,
                              TextureTarget target) implements AutoCloseable {
        @Override
        public void close() {
            target.destroyBuffers();
        }
    }
}
