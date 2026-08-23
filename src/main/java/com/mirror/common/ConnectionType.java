package com.mirror.common;

import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import java.util.Locale;

public enum ConnectionType implements StringRepresentable {
    SINGLE(0),
    CENTER(15),
    TOP(14),
    BOTTOM(13),
    LEFT(11),
    RIGHT(7),
    TOP_LEFT(10),
    TOP_RIGHT(6),
    BOTTOM_LEFT(9),
    BOTTOM_RIGHT(5),
    H_LEFT(8),
    H_RIGHT(4),
    H_MIDDLE(12),
    V_BOTTOM(1),
    V_TOP(2),
    V_MIDDLE(3);

    private static final int BIT_UP = 1;
    private static final int BIT_DOWN = 2;
    private static final int BIT_LEFT = 4;
    private static final int BIT_RIGHT = 8;
    public static final EnumProperty<ConnectionType> PROPERTY =
            EnumProperty.create("connection", ConnectionType.class);

    private final int edgeMask;

    ConnectionType(int edgeMask) {
        this.edgeMask = edgeMask;
    }

    public static ConnectionType fromConnections(boolean up, boolean down, boolean left, boolean right) {
        int mask = (up ? BIT_UP : 0) | (down ? BIT_DOWN : 0) |
                (left ? BIT_LEFT : 0) | (right ? BIT_RIGHT : 0);
        return switch (mask) {
            case 15 -> CENTER;
            case 14 -> TOP;
            case 13 -> BOTTOM;
            case 11 -> LEFT;
            case 7 -> RIGHT;
            case 10 -> TOP_LEFT;
            case 6 -> TOP_RIGHT;
            case 9 -> BOTTOM_LEFT;
            case 5 -> BOTTOM_RIGHT;
            case 8 -> H_LEFT;
            case 4 -> H_RIGHT;
            case 12 -> H_MIDDLE;
            case 1 -> V_BOTTOM;
            case 2 -> V_TOP;
            case 3 -> V_MIDDLE;
            default -> SINGLE;
        };
    }

    public boolean hasEdge(LocalSide side) {
        int bit = switch (side) {
            case UP -> BIT_UP;
            case DOWN -> BIT_DOWN;
            case LEFT -> BIT_LEFT;
            case RIGHT -> BIT_RIGHT;
        };
        return (edgeMask & bit) == 0;
    }

    public boolean isConnected(LocalSide side) {
        return !hasEdge(side);
    }

    public boolean isConnected(Direction worldSide, Direction facing) {
        if (worldSide == Direction.UP) return isConnected(LocalSide.UP);
        if (worldSide == Direction.DOWN) return isConnected(LocalSide.DOWN);
        if (worldSide == facing.getClockWise()) return isConnected(LocalSide.LEFT);
        if (worldSide == facing.getCounterClockWise()) return isConnected(LocalSide.RIGHT);
        return false;
    }

    public boolean isSingle() {
        return this == SINGLE;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public enum LocalSide {
        UP, DOWN, LEFT, RIGHT
    }
}
