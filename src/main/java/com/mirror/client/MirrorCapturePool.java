package com.mirror.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import org.lwjgl.opengl.GL11C;
import com.mirror.compat.ThirdPartyFrame;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns stable render targets for mirror pipeline slots.
 *
 * <p>Ordinary captures use rectangular power-of-two buckets. Shader captures use one square
 * target per recursion/resolution slot.
 * A square target keeps Oculus' viewport aspect and the centered capture projection identical,
 * while {@link MirrorProjection#fitViewport(int, int)} adds only the overscan required to contain
 * the physical mirror aperture and returns the exact UV crop used during composition. Keeping the
 * shader slot one-dimensional also prevents arbitrary mirror aspect ratios from multiplying complete
 * Oculus pipelines. Unused targets expire independently of those compiled pipelines.</p>
 */
public final class MirrorCapturePool {
    static final int MIN_BUCKET_SIZE = 16;
    static final int MIN_SHADER_LONG_EDGE = 256;
    /**
     * Shader captures use a small, explicit set of stable capacities. Power-of-two rounding
     * turns a modest overscan request (for example 533 px) into a 1024 px target, doubling every
     * G-buffer and post-process attachment. These tiers keep the pipeline key stable while
     * avoiding that pathological jump.
     */
    private static final int[] SHADER_BUCKETS = {256, 384, 512, 768, 1024};
    /**
     * Shader packs run several full-screen passes for every mirror capture. Letting a transient
     * projected-area spike promote the target without a bound makes that work quadratic and can
     * stall the render thread while Oculus allocates a new attachment set.
     */
    static final int MAX_SHADER_CAPTURE_EDGE = 1024;
    private static final Map<MirrorPassContext.PipelineSlot, CaptureSlot> SLOTS = new HashMap<>();
    private static final Map<MirrorPassContext.PipelineSlot, Long> LAST_USED = new HashMap<>();
    private static int maximumTextureSize;

    private MirrorCapturePool() {
    }

    /** Number of shader capacity slots that should be warmed for each recursion depth. */
    public static int shaderBucketCount() {
        return SHADER_BUCKETS.length;
    }

    public static CaptureSlot acquire(int recursionDepth, int requestedWidth, int requestedHeight) {
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalArgumentException("mirror capture dimensions must be positive");
        }
        boolean shaderCapture = OculusCompat.isShaderPackInUse();
        int minimumLongEdge = shaderCapture ? MIN_SHADER_LONG_EDGE : MIN_BUCKET_SIZE;
        int captureWidth = compensatedRequestSize(requestedWidth, shaderCapture);
        int captureHeight = compensatedRequestSize(requestedHeight, shaderCapture);
        MirrorPassContext.ResolutionBucket bucket = shaderCapture
                ? shaderBucketSize(captureWidth, captureHeight,
                maximumCaptureSize(true), minimumLongEdge)
                : new MirrorPassContext.ResolutionBucket(
                        Math.min(maximumTextureSize(), Math.max(MIN_BUCKET_SIZE, nextPowerOfTwo(captureWidth))),
                        Math.min(maximumTextureSize(), Math.max(MIN_BUCKET_SIZE, nextPowerOfTwo(captureHeight))));
        MirrorPassContext.PipelineSlot key = new MirrorPassContext.PipelineSlot(recursionDepth, bucket);
        LAST_USED.put(key, ThirdPartyFrame.id());
        return SLOTS.computeIfAbsent(key, ignored -> {
            MirrorDiagnostics.recordResource("CAPTURE_ALLOC", -1, bucket.widthBucket(), bucket.heightBucket());
            return new CaptureSlot(key,
                    new TextureTarget(bucket.widthBucket(), bucket.heightBucket(), true, false));
        });
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

    private static MirrorPassContext.ResolutionBucket shaderBucketSize(int requestedWidth,
                                                                         int requestedHeight,
                                                                         int maximumTextureSize,
                                                                         int minimumLongEdge) {
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalArgumentException("mirror capture dimensions must be positive");
        }
        int maximum = Math.max(MIN_SHADER_LONG_EDGE, maximumTextureSize);
        int required = Math.max(minimumLongEdge, Math.max(requestedWidth, requestedHeight));
        int side = maximum;
        for (int bucket : SHADER_BUCKETS) {
            if (bucket >= required) {
                side = Math.min(bucket, maximum);
                break;
            }
        }
        return new MirrorPassContext.ResolutionBucket(side, side);
    }

    static int maximumTextureSize() {
        if (maximumTextureSize <= 0) {
            maximumTextureSize = GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE);
        }
        return maximumTextureSize;
    }

    static int maximumCaptureSize(boolean shaderCapture) {
        return shaderCapture
                ? Math.min(maximumTextureSize(), MAX_SHADER_CAPTURE_EDGE)
                : maximumTextureSize();
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 1) return 1;
        if (value >= (1 << 30)) return Integer.MAX_VALUE;
        return Integer.highestOneBit(value - 1) << 1;
    }

    public static void clear() {
        SLOTS.values().forEach(CaptureSlot::close);
        SLOTS.clear();
        LAST_USED.clear();
    }

    static void evictUnused() {
        var iterator = SLOTS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (ThirdPartyFrame.id() - LAST_USED.get(entry.getKey()) <= 600) continue;
            entry.getValue().close();
            LAST_USED.remove(entry.getKey());
            iterator.remove();
        }
    }

    public record CaptureSlot(MirrorPassContext.PipelineSlot pipelineSlot,
                              TextureTarget target) implements AutoCloseable {
        @Override
        public void close() {
            MirrorDiagnostics.recordResource("CAPTURE_FREE", -1, target.width, target.height);
            target.destroyBuffers();
        }
    }
}
