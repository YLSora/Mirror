package com.mirror.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import org.lwjgl.opengl.GL11C;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns stable render targets for mirror pipeline slots.
 *
 * <p>Mirror shader captures use one square power-of-two target per recursion/resolution slot.
 * A square target keeps Oculus' viewport aspect and the centered capture projection identical,
 * while {@link MirrorProjection#fitViewport(int, int)} adds only the overscan required to contain
 * the physical mirror aperture and returns the exact UV crop used during composition. Keeping the
 * slot one-dimensional also prevents arbitrary mirror aspect ratios from multiplying complete
 * Oculus pipelines.</p>
 */
public final class MirrorCapturePool {
    static final int MIN_BUCKET_SIZE = 16;
    static final int MIN_SHADER_LONG_EDGE = 256;
    private static final Map<MirrorPassContext.PipelineSlot, CaptureSlot> SLOTS = new HashMap<>();
    private static int maximumTextureSize;

    private MirrorCapturePool() {
    }

    public static CaptureSlot acquire(int recursionDepth, int requestedWidth, int requestedHeight) {
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalArgumentException("mirror capture dimensions must be positive");
        }
        boolean shaderCapture = OculusCompat.isShaderPackInUse();
        int minimumLongEdge = shaderCapture ? MIN_SHADER_LONG_EDGE : MIN_BUCKET_SIZE;
        int captureWidth = compensatedRequestSize(requestedWidth, shaderCapture);
        int captureHeight = compensatedRequestSize(requestedHeight, shaderCapture);
        MirrorPassContext.ResolutionBucket bucket = bucketSize(
                captureWidth, captureHeight, maximumTextureSize(), minimumLongEdge);
        MirrorPassContext.PipelineSlot key = new MirrorPassContext.PipelineSlot(recursionDepth, bucket);
        return SLOTS.computeIfAbsent(key, ignored -> new CaptureSlot(key,
                new TextureTarget(bucket.widthBucket(), bucket.heightBucket(), true, false)));
    }

    static int compensatedRequestSize(int requestedSize, boolean shaderCapture) {
        if (requestedSize <= 0) {
            throw new IllegalArgumentException("mirror capture dimension must be positive");
        }
        if (!shaderCapture) return requestedSize;
        double scaled = Math.ceil(requestedSize * (double) MirrorProjectionStabilizer.shaderSamplingCompensation());
        return scaled >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    static MirrorPassContext.ResolutionBucket bucketSize(int requestedWidth, int requestedHeight,
                                                          int maximumTextureSize) {
        return bucketSize(requestedWidth, requestedHeight, maximumTextureSize, MIN_BUCKET_SIZE);
    }

    static MirrorPassContext.ResolutionBucket bucketSize(int requestedWidth, int requestedHeight,
                                                          int maximumTextureSize,
                                                          int minimumLongEdge) {
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalArgumentException("mirror capture dimensions must be positive");
        }
        int maximum = Math.max(MIN_BUCKET_SIZE, maximumTextureSize);
        int requiredLongEdge = Math.max(requestedWidth, requestedHeight);
        int required = Math.max(MIN_BUCKET_SIZE, Math.max(minimumLongEdge, requiredLongEdge));
        int side = Math.min(maximum, nextPowerOfTwo(required));
        return new MirrorPassContext.ResolutionBucket(side, side);
    }

    private static int maximumTextureSize() {
        if (maximumTextureSize <= 0) {
            maximumTextureSize = GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE);
        }
        return maximumTextureSize;
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) return 1;
        if (value >= (1 << 30)) return Integer.MAX_VALUE;
        return Integer.highestOneBit(value - 1) << 1;
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
