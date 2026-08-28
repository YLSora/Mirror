package com.mirror.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MirrorCapturePoolTest {
    @Test
    void bucketsLongEdgeIntoSquareStableSlot() {
        assertEquals(new MirrorPassContext.ResolutionBucket(16, 16),
                MirrorCapturePool.bucketSize(1, 8, 4096));
        assertEquals(new MirrorPassContext.ResolutionBucket(256, 256),
                MirrorCapturePool.bucketSize(240, 128, 4096));
        assertEquals(new MirrorPassContext.ResolutionBucket(512, 512),
                MirrorCapturePool.bucketSize(257, 300, 4096));
    }

    @Test
    void shaderMinimumAppliesToTheSingleCaptureSide() {
        assertEquals(new MirrorPassContext.ResolutionBucket(256, 256),
                MirrorCapturePool.bucketSize(112, 28, 4096, 256));
        assertEquals(new MirrorPassContext.ResolutionBucket(256, 256),
                MirrorCapturePool.bucketSize(112, 112, 4096, 256));
    }

    @Test
    void clampsSquareTargetToTheGpuLimit() {
        assertEquals(new MirrorPassContext.ResolutionBucket(4096, 4096),
                MirrorCapturePool.bucketSize(5000, 3000, 4096));
    }
    @Test
    void shaderCaptureCompensatesForGuardBandSamplingLoss() {
        assertEquals(300, MirrorCapturePool.compensatedRequestSize(300, false));
        assertEquals(400, MirrorCapturePool.compensatedRequestSize(300, true));
        assertEquals(new MirrorPassContext.ResolutionBucket(512, 512),
                MirrorCapturePool.bucketSize(
                        MirrorCapturePool.compensatedRequestSize(300, true),
                        MirrorCapturePool.compensatedRequestSize(120, true),
                        4096, 256));
    }

}
