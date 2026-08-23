package com.mirror.client;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorReflectionTest {
    @Test
    void reflectsEyeAcrossPlane() {
        MirrorReflection reflection = MirrorReflection.compute(
                Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 2, 3));

        assertEquals(new Vec3(0, 2, -3), reflection.reflectedEye());
        assertEquals(3.0, reflection.signedDistance());
        assertTrue(reflection.viewerInFront());
    }

    @Test
    void rejectsViewerBehindPlane() {
        MirrorReflection reflection = MirrorReflection.compute(
                Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 0, -1));

        assertFalse(reflection.viewerInFront());
    }
}
