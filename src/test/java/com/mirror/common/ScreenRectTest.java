package com.mirror.common;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScreenRectTest {
    @Test
    void projectsAndReconstructsLocalCoordinates() {
        ScreenRect screen = new ScreenRect(new Vec3(10, 64, 10), new Vec3(0, 0, 1), 2, 3);
        Vec2 local = new Vec2(0.25f, -0.25f);

        Vec3 world = screen.localToWorld(local);
        Vec2 projected = screen.projectLocal(world);

        assertNotNull(projected);
        assertEquals(local.x, projected.x, 1e-6f);
        assertEquals(local.y, projected.y, 1e-6f);
    }

    @Test
    void rejectsPointsOutsideTheScreen() {
        ScreenRect screen = new ScreenRect(Vec3.ZERO, new Vec3(0, 0, -1), 2, 2);

        assertNull(screen.projectLocal(new Vec3(1.1, 0, 0)));
    }
}
