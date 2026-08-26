package com.mirror.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MirrorCapturePoolTest {
    @Test
    void roundsCaptureDimensionsToASquarePowerOfTwoSlot() {
        assertEquals(16, MirrorCapturePool.bucketSide(1, 8, 4096));
        assertEquals(256, MirrorCapturePool.bucketSide(240, 128, 4096));
        assertEquals(512, MirrorCapturePool.bucketSide(257, 300, 4096));
    }

    @Test
    void clampsCaptureSlotToTheGpuLimit() {
        assertEquals(4096, MirrorCapturePool.bucketSide(5000, 3000, 4096));
    }
}
