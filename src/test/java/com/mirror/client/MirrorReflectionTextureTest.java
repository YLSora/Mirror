package com.mirror.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorReflectionTextureTest {
    @Test
    void reusesCapacityWhenThePhysicalMirrorShrinks() {
        assertTrue(MirrorReflectionTexture.canReuseCapacity(240, 240, 112, 240));
    }

    @Test
    void smallerDemandReusesSurfaceCapacity() {
        assertTrue(MirrorReflectionTexture.canReuseCapacity(240, 240, 120, 120));
    }

    @Test
    void growingMirrorsAllocateEnoughCapacity() {
        assertFalse(MirrorReflectionTexture.canReuseCapacity(112, 112, 240, 240));
    }
}
