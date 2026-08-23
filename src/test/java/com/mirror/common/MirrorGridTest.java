package com.mirror.common;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
