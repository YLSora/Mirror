package com.mirror.client;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void reflectsAcrossFloorAndCeiling() {
        Vec3 plane = new Vec3(0, 64, 0);
        MirrorReflection floor = MirrorReflection.compute(plane, new Vec3(0, 1, 0), new Vec3(2, 67, 4));
        MirrorReflection ceiling = MirrorReflection.compute(plane, new Vec3(0, -1, 0), new Vec3(2, 61, 4));
        assertEquals(new Vec3(2, 61, 4), floor.reflectedEye());
        assertEquals(new Vec3(2, 67, 4), ceiling.reflectedEye());
        assertTrue(floor.viewerInFront());
        assertTrue(ceiling.viewerInFront());
        assertFalse(MirrorReflection.compute(plane, new Vec3(0, 1, 0), new Vec3(2, 61, 4)).viewerInFront());
        assertFalse(MirrorReflection.compute(plane, new Vec3(0, -1, 0), new Vec3(2, 67, 4)).viewerInFront());
    }

    @Test
    void recursivePathsCanMixFloorCeilingAndWallMirrors() {
        var path = List.of(
                new MirrorLevelRenderer.ReflectionPlane(Vec3.ZERO, new Vec3(0, 1, 0)),
                new MirrorLevelRenderer.ReflectionPlane(new Vec3(0, 6, 0), new Vec3(0, -1, 0)),
                new MirrorLevelRenderer.ReflectionPlane(Vec3.ZERO, new Vec3(0, 0, 1)));
        assertEquals(new Vec3(2, 15, -4), MirrorLevelRenderer.resolveReflectionPath(new Vec3(2, 3, 4), path));
        assertNull(MirrorLevelRenderer.resolveReflectionPath(new Vec3(2, -1, 4), path));
    }
}
