package com.mirror.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorReflectionTextureTest {
    @Test
    void reusesCapacityWhenThePhysicalMirrorShrinks() {
        assertTrue(MirrorReflectionTexture.canReuseForChangedLayout(
                240, 240, 30, 30, 112, 240, 14, 30));
    }

    @Test
    void lodChangesDoNotPinAHighResolutionTexture() {
        assertFalse(MirrorReflectionTexture.canReuseForChangedLayout(
                240, 240, 30, 30, 120, 120, 30, 30));
    }

    @Test
    void growingMirrorsAllocateEnoughCapacity() {
        assertFalse(MirrorReflectionTexture.canReuseForChangedLayout(
                112, 112, 14, 14, 240, 240, 30, 30));
    }
}
