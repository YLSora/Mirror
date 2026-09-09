package com.mirror.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorGridTest {
    @Test
    void selectsLargestFilledRectangle() {
        Set<MirrorGrid.Cell> cells = cells(0, 0, 1, 0, 0, 1, 1, 1, 2, 0);

        MirrorGrid.Rectangle rectangle = MirrorGrid.findLargestRectangle(cells, 8, false);

        assertEquals(2, rectangle.width());
        assertEquals(2, rectangle.height());
        assertEquals(4, rectangle.area());
    }

    @Test
    void squareModeRejectsNonSquareLargestRectangle() {
        Set<MirrorGrid.Cell> cells = cells(0, 0, 1, 0, 2, 0, 0, 1, 1, 1, 2, 1);

        MirrorGrid.Rectangle rectangle = MirrorGrid.findLargestRectangle(cells, 8, true);

        assertEquals(2, rectangle.width());
        assertEquals(2, rectangle.height());
        assertEquals(4, rectangle.area());
    }

    @Test
    void maximumSizeBoundsSelection() {
        Set<MirrorGrid.Cell> cells = cells(
                0, 0, 1, 0, 2, 0,
                0, 1, 1, 1, 2, 1,
                0, 2, 1, 2, 2, 2);

        MirrorGrid.Rectangle rectangle = MirrorGrid.findLargestRectangle(cells, 2, false);

        assertEquals(2, rectangle.width());
        assertEquals(2, rectangle.height());
        assertEquals(4, rectangle.area());
    }

    @Test
    void bottomLeftIsTheOnlyOwnerForAllFacingDirections() {
        assertTrue(ConnectionType.SINGLE.isMaster());
        assertTrue(ConnectionType.V_BOTTOM.isMaster());
        assertTrue(ConnectionType.H_LEFT.isMaster());
        assertTrue(ConnectionType.BOTTOM_LEFT.isMaster());
        assertFalse(ConnectionType.TOP_RIGHT.isMaster());
    }

    @Test
    void localHorizontalAxisAlwaysUsesFacingCounterClockwise() {
        BlockPos origin = new BlockPos(10, 20, 30);
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST,
                Direction.SOUTH, Direction.WEST}) {
            assertEquals(origin.relative(facing.getCounterClockWise()),
                    MirrorGrid.toWorld(origin, facing, 1, 0));
            assertEquals(origin.above(), MirrorGrid.toWorld(origin, facing, 0, 1));
        }
    }

    @Test
    void horizontalMirrorsUseTheXZPlane() {
        BlockPos origin = new BlockPos(10, 20, 30);
        assertEquals(new BlockPos(8, 20, 33), MirrorGrid.toWorld(origin, Direction.UP, 2, 3));
        assertEquals(new BlockPos(8, 20, 27), MirrorGrid.toWorld(origin, Direction.DOWN, 2, 3));
    }

    @Test
    void rotatedConnectionsFollowTheirWorldNeighbors() {
        for (Direction facing : Direction.values()) {
            for (ConnectionType connection : ConnectionType.values()) {
                for (Rotation rotation : Rotation.values()) {
                    ConnectionType rotated = connection.transform(facing, rotation::rotate);
                    for (Direction side : Direction.values()) {
                        assertEquals(connection.isConnected(side, facing),
                                rotated.isConnected(rotation.rotate(side), rotation.rotate(facing)));
                    }
                }
                for (Mirror mirror : Mirror.values()) {
                    ConnectionType mirrored = connection.transform(facing, mirror::mirror);
                    for (Direction side : Direction.values()) {
                        assertEquals(connection.isConnected(side, facing),
                                mirrored.isConnected(mirror.mirror(side), mirror.mirror(facing)));
                    }
                }
            }
        }
    }

    private static Set<MirrorGrid.Cell> cells(int... coordinates) {
        Set<MirrorGrid.Cell> result = new HashSet<>();
        for (int i = 0; i < coordinates.length; i += 2) {
            int x = coordinates[i];
            int y = coordinates[i + 1];
            result.add(new MirrorGrid.Cell(new BlockPos(x, y, 0), x, y));
        }
        return result;
    }
}
