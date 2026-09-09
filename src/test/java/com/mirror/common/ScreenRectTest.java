package com.mirror.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScreenRectTest {
    @ParameterizedTest
    @EnumSource(MirrorOrientation.class)
    void projectsAndReconstructsLocalCoordinates(MirrorOrientation orientation) {
        ScreenRect screen = new ScreenRect(new Vec3(10, 64, 10), orientation, 2, 3);
        Vec2 local = new Vec2(0.25f, -0.25f);

        Vec3 world = screen.localToWorld(local);
        Vec2 projected = screen.projectLocal(world);

        assertNotNull(projected);
        assertEquals(local.x, projected.x, 1e-6f);
        assertEquals(local.y, projected.y, 1e-6f);
    }

    @ParameterizedTest
    @EnumSource(MirrorOrientation.class)
    void rejectsPointsOutsideTheScreen(MirrorOrientation orientation) {
        ScreenRect screen = new ScreenRect(Vec3.ZERO, orientation, 2, 2);

        assertNull(screen.projectLocal(screen.right().scale(1.1)));
        assertNull(screen.projectLocal(screen.up().scale(1.1)));
    }

    @Test
    void horizontalRectanglesExtendInTheirModelDirections() {
        BlockPos master = new BlockPos(10, 64, 10);
        assertEquals(new Vec3(9.5, 65, 11),
                ScreenRect.fromMaster(master, Direction.UP, 3, 2, 0).center());
        assertEquals(new Vec3(9.5, 64, 10),
                ScreenRect.fromMaster(master, Direction.DOWN, 3, 2, 0).center());
        assertEquals(new Vec3(9.5, 64.125, 11),
                ScreenRect.fromMaster(master, Direction.UP, 3, 2, 14.0 / 16.0).center());
        assertEquals(new Vec3(9.5, 64.875, 10),
                ScreenRect.fromMaster(master, Direction.DOWN, 3, 2, 14.0 / 16.0).center());
    }

    @Test
    void verticalRectangleCenterExtendsTowardItsConnectedCells() {
        assertEquals(new Vec3(9.5, 65, 10), ScreenRect.fromMaster(
                new BlockPos(10, 64, 10), Direction.NORTH, 3, 2, 0).center());
    }
}
