package com.mirror.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Fixed local axes as seen from the reflective side of each mirror. */
public enum MirrorOrientation {
    NORTH(Direction.NORTH, Direction.WEST, Direction.UP),
    EAST(Direction.EAST, Direction.NORTH, Direction.UP),
    SOUTH(Direction.SOUTH, Direction.EAST, Direction.UP),
    WEST(Direction.WEST, Direction.SOUTH, Direction.UP),
    UP(Direction.UP, Direction.WEST, Direction.SOUTH),
    DOWN(Direction.DOWN, Direction.WEST, Direction.NORTH);

    private final Direction facing;
    private final Direction right;
    private final Direction up;

    MirrorOrientation(Direction facing, Direction right, Direction up) {
        this.facing = facing;
        this.right = right;
        this.up = up;
    }

    public static MirrorOrientation of(Direction facing) {
        return switch (facing) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }

    public Direction direction(ConnectionType.LocalSide side) {
        return switch (side) {
            case RIGHT -> right;
            case LEFT -> right.getOpposite();
            case UP -> up;
            case DOWN -> up.getOpposite();
        };
    }

    public Vec3 normal() {
        return Vec3.atLowerCornerOf(facing.getNormal());
    }

    public Vec3 right() {
        return Vec3.atLowerCornerOf(right.getNormal());
    }

    public Vec3 up() {
        return Vec3.atLowerCornerOf(up.getNormal());
    }

    public int localX(Direction direction) {
        return direction == right ? 1 : direction == right.getOpposite() ? -1 : 0;
    }

    public int localY(Direction direction) {
        return direction == up ? 1 : direction == up.getOpposite() ? -1 : 0;
    }

    public BlockPos toWorld(BlockPos origin, int x, int y) {
        return origin.relative(right, x).relative(up, y);
    }

    // A camera behind the mirror looks along its normal, with the opposite horizontal axis.
    public float yaw() {
        return facing.getAxis().isVertical() ? 180.0f : facing.toYRot();
    }

    public float pitch() {
        return facing.getAxis().isVertical() ? -90.0f * facing.getStepY() : 0.0f;
    }
}
