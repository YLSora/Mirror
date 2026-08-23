package com.mirror.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rebuilds only rectangular groups of coplanar mirrors. */
public final class MirrorGrid {
    private static final ThreadLocal<Boolean> REBUILDING = ThreadLocal.withInitial(() -> false);

    private MirrorGrid() {
    }

    public static void rebuildAround(Level level, BlockPos seed) {
        if (level.isClientSide || REBUILDING.get()) return;
        BlockState state = level.getBlockState(seed);
        if (!(state.getBlock() instanceof MirrorBlock mirror)) return;
        rebuild(level, seed, mirror);
    }

    public static void rebuildAround(Level level, BlockPos seed, BlockState reference) {
        if (level.isClientSide || REBUILDING.get()) return;
        if (!(reference.getBlock() instanceof MirrorBlock mirror)) return;
        BlockState state = level.getBlockState(seed);
        if (!mirror.connectionMatches(reference, state)) return;
        rebuild(level, seed, mirror);
    }

    private static void rebuild(Level level, BlockPos seed, MirrorBlock mirror) {
        REBUILDING.set(true);
        try {
            int max = mirror.maxConnectedSize();
            Set<Cell> component = collectComponent(level, seed, mirror, max);
            Rectangle selected = findLargestRectangle(component, max, mirror.squareAspectRatio());

            for (Cell cell : component) {
                BlockState current = level.getBlockState(cell.pos());
                if (current.getBlock() == mirror) {
                    setConnection(level, cell.pos(), current, ConnectionType.SINGLE);
                    if (level.getBlockEntity(cell.pos()) instanceof MirrorBlockEntity entity) {
                        entity.setConnectionSize(1, 1);
                    }
                }
            }

            for (int y = selected.bottom(); y < selected.bottom() + selected.height(); y++) {
                for (int x = selected.left(); x < selected.left() + selected.width(); x++) {
                    BlockPos pos = toWorld(seed, mirror.getStateFacing(level, seed), x, y);
                    BlockState current = level.getBlockState(pos);
                    if (current.getBlock() == mirror) {
                        ConnectionType type = ConnectionType.fromConnections(
                                y < selected.bottom() + selected.height() - 1,
                                y > selected.bottom(),
                                x > selected.left(),
                                x < selected.left() + selected.width() - 1);
                        setConnection(level, pos, current, type);
                    }
                }
            }

            BlockPos masterPos = toWorld(seed, mirror.getStateFacing(level, seed), selected.left(), selected.bottom());
            if (level.getBlockEntity(masterPos) instanceof MirrorBlockEntity master) {
                master.setConnectionSize(selected.width(), selected.height());
            }
        } finally {
            REBUILDING.set(false);
        }
    }

    private static Set<Cell> collectComponent(Level level, BlockPos seed, MirrorBlock mirror, int max) {
        Set<Cell> result = new HashSet<>();
        ArrayDeque<Cell> pending = new ArrayDeque<>();
        Direction facing = mirror.getStateFacing(level, seed);
        pending.add(new Cell(seed, 0, 0));
        while (!pending.isEmpty() && result.size() < max * max) {
            Cell cell = pending.removeFirst();
            BlockState state = level.getBlockState(cell.pos());
            if (!mirror.connectionMatches(level.getBlockState(seed), state)) continue;
            if (!result.add(cell)) continue;
            for (Direction direction : new Direction[]{Direction.UP, Direction.DOWN,
                    facing.getClockWise(), facing.getCounterClockWise()}) {
                BlockPos next = cell.pos().relative(direction);
                int nextX = cell.x() + localX(direction, facing);
                int nextY = cell.y() + localY(direction);
                if (Math.abs(nextX) <= max && Math.abs(nextY) <= max) {
                    pending.add(new Cell(next, nextX, nextY));
                }
            }
        }
        result.removeIf(cell -> !mirror.connectionMatches(level.getBlockState(seed), level.getBlockState(cell.pos())));
        return result;
    }

    private static int localX(Direction direction, Direction facing) {
        return direction == facing.getCounterClockWise() ? 1 : direction == facing.getClockWise() ? -1 : 0;
    }

    private static int localY(Direction direction) {
        return direction == Direction.UP ? 1 : direction == Direction.DOWN ? -1 : 0;
    }

    static Rectangle findLargestRectangle(Set<Cell> component, int max, boolean squareOnly) {
        if (component.isEmpty()) return new Rectangle(0, 0, 1, 1);
        Set<Long> occupied = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (Cell cell : component) {
            occupied.add(key(cell.x(), cell.y()));
            minX = Math.min(minX, cell.x());
            maxX = Math.max(maxX, cell.x());
            minY = Math.min(minY, cell.y());
            maxY = Math.max(maxY, cell.y());
        }

        Rectangle best = new Rectangle(0, 0, 1, 1);
        for (int left = minX; left <= maxX; left++) {
            for (int bottom = minY; bottom <= maxY; bottom++) {
                for (int width = 1; width <= max && left + width - 1 <= maxX; width++) {
                    for (int height = 1; height <= max && bottom + height - 1 <= maxY; height++) {
                        if (squareOnly && width != height) continue;
                        Rectangle candidate = new Rectangle(left, bottom, width, height);
                        if (candidate.area() <= best.area() || !containsAll(occupied, candidate)) continue;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static boolean containsAll(Set<Long> occupied, Rectangle rectangle) {
        for (int y = rectangle.bottom(); y < rectangle.bottom() + rectangle.height(); y++) {
            for (int x = rectangle.left(); x < rectangle.left() + rectangle.width(); x++) {
                if (!occupied.contains(key(x, y))) return false;
            }
        }
        return true;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static void setConnection(Level level, BlockPos pos, BlockState state, ConnectionType type) {
        if (state.getValue(MirrorBlock.CONNECTION) != type) {
            level.setBlockAndUpdate(pos, state.setValue(MirrorBlock.CONNECTION, type));
        }
    }

    public static BlockPos toWorld(BlockPos origin, Direction facing, int x, int y) {
        return origin.relative(facing.getCounterClockWise(), x).above(y);
    }

    public record Cell(BlockPos pos, int x, int y) {
    }

    public record Rectangle(int left, int bottom, int width, int height) {
        public int area() {
            return width * height;
        }
    }
}
