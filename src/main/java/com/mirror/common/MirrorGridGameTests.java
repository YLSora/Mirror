package com.mirror.common;

import com.mirror.MirrorMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;

/** In-world ownership tests; run with the Forge gameTestServer task. */
@GameTestHolder(MirrorMod.MOD_ID)
public final class MirrorGridGameTests {
    private static final BlockPos ORIGIN = new BlockPos(1, 1, 1);

    private MirrorGridGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "minecraft")
    public static void northTwoByTwoHasOneOwner(GameTestHelper helper) {
        verifyTwoByTwo(helper, Direction.NORTH, false);
    }

    @GameTest(template = "empty", templateNamespace = "minecraft")
    public static void eastTwoByTwoHasOneOwner(GameTestHelper helper) {
        verifyTwoByTwo(helper, Direction.EAST, false);
    }

    @GameTest(template = "empty", templateNamespace = "minecraft")
    public static void southTwoByTwoHasOneOwner(GameTestHelper helper) {
        verifyTwoByTwo(helper, Direction.SOUTH, false);
    }

    @GameTest(template = "empty", templateNamespace = "minecraft")
    public static void westTwoByTwoHasOneOwner(GameTestHelper helper) {
        verifyTwoByTwo(helper, Direction.WEST, false);
    }

    @GameTest(template = "empty", templateNamespace = "minecraft")
    public static void oneByNPromotesAfterMasterRemoval(GameTestHelper helper) {
        MirrorBlock mirror = MirrorMod.MIRROR.get();
        Direction facing = Direction.NORTH;
        BlockState state = mirror.defaultBlockState().setValue(MirrorBlock.FACING, facing);
        BlockPos middle = ORIGIN.above();
        BlockPos top = middle.above();
        helper.setBlock(ORIGIN, state);
        helper.setBlock(middle, state);
        helper.setBlock(top, state);
        helper.runAtTickTime(4, () -> {
            helper.assertBlockProperty(ORIGIN, MirrorBlock.CONNECTION, ConnectionType.V_BOTTOM);
            helper.assertTrue(helper.getBlockEntity(ORIGIN) instanceof MirrorBlockEntity,
                    "the bottom-left cell must own the block entity");
            helper.assertTrue(helper.getBlockEntity(middle) == null,
                    "the vertical non-master must not own a block entity");
            java.util.UUID ownerId = ((MirrorBlockEntity) helper.getBlockEntity(ORIGIN)).getId();
            helper.destroyBlock(ORIGIN);
            helper.runAtTickTime(2, () -> {
                helper.assertBlockProperty(middle, MirrorBlock.CONNECTION, ConnectionType.V_BOTTOM);
                helper.assertTrue(helper.getBlockEntity(middle) instanceof MirrorBlockEntity promoted
                                && promoted.getId().equals(ownerId),
                        "the next bottom cell must be promoted after removing the master");
                helper.assertTrue(helper.getBlockEntity(top) == null,
                        "the promoted vertical non-master must not own a block entity");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "minecraft")
    public static void nByOneHasOneOwner(GameTestHelper helper) {
        MirrorBlock mirror = MirrorMod.MIRROR.get();
        Direction facing = Direction.NORTH;
        BlockState state = mirror.defaultBlockState().setValue(MirrorBlock.FACING, facing);
        BlockPos middle = ORIGIN.relative(facing.getCounterClockWise());
        BlockPos right = middle.relative(facing.getCounterClockWise());
        helper.setBlock(ORIGIN, state);
        helper.setBlock(middle, state);
        helper.setBlock(right, state);
        helper.runAtTickTime(4, () -> {
            helper.assertBlockProperty(ORIGIN, MirrorBlock.CONNECTION, ConnectionType.H_LEFT);
            helper.assertTrue(helper.getBlockEntity(ORIGIN) instanceof MirrorBlockEntity,
                    "the horizontal bottom-left cell must own the block entity");
            helper.assertTrue(helper.getBlockEntity(middle) == null,
                    "the horizontal middle cell must not own a block entity");
            helper.assertTrue(helper.getBlockEntity(right) == null,
                    "the horizontal right cell must not own a block entity");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "minecraft")
    public static void farMirrorUsesTheSameOwnershipRule(GameTestHelper helper) {
        verifyTwoByTwo(helper, Direction.NORTH, true);
    }

    @GameTest(template = "empty", templateNamespace = "minecraft")
    public static void nearAndFarHalvesStaySeparate(GameTestHelper helper) {
        MirrorBlock mirror = MirrorMod.MIRROR.get();
        BlockState near = mirror.defaultBlockState()
                .setValue(MirrorBlock.FACING, Direction.NORTH)
                .setValue(MirrorBlock.FAR, false);
        BlockState far = near.setValue(MirrorBlock.FAR, true);
        BlockPos neighbour = ORIGIN.relative(Direction.WEST);
        helper.setBlock(ORIGIN, near);
        helper.setBlock(neighbour, far);
        helper.runAtTickTime(4, () -> {
            helper.assertBlockProperty(ORIGIN, MirrorBlock.CONNECTION, ConnectionType.SINGLE);
            helper.assertBlockProperty(neighbour, MirrorBlock.CONNECTION, ConnectionType.SINGLE);
            helper.assertTrue(helper.getBlockEntity(ORIGIN) instanceof MirrorBlockEntity,
                    "the near half must own its separate mirror");
            helper.assertTrue(helper.getBlockEntity(neighbour) instanceof MirrorBlockEntity,
                    "the far half must own its separate mirror");
            helper.succeed();
        });
    }

    private static void verifyTwoByTwo(GameTestHelper helper, Direction facing, boolean far) {
        MirrorBlock mirror = MirrorMod.MIRROR.get();
        BlockState state = mirror.defaultBlockState()
                .setValue(MirrorBlock.FACING, facing)
                .setValue(MirrorBlock.FAR, far);
        BlockPos localRight = ORIGIN.relative(facing.getCounterClockWise());
        BlockPos upper = ORIGIN.above();
        BlockPos upperRight = localRight.above();
        helper.setBlock(ORIGIN, state);
        helper.setBlock(localRight, state);
        helper.setBlock(upper, state);
        helper.setBlock(upperRight, state);
        helper.runAtTickTime(4, () -> {
            helper.assertBlockProperty(ORIGIN, MirrorBlock.CONNECTION, ConnectionType.BOTTOM_LEFT);
            helper.assertBlockProperty(localRight, MirrorBlock.CONNECTION, ConnectionType.BOTTOM_RIGHT);
            helper.assertBlockProperty(upper, MirrorBlock.CONNECTION, ConnectionType.TOP_LEFT);
            helper.assertBlockProperty(upperRight, MirrorBlock.CONNECTION, ConnectionType.TOP_RIGHT);
            helper.assertTrue(helper.getBlockEntity(ORIGIN) instanceof MirrorBlockEntity,
                    "only the rectangle bottom-left must own the block entity");
            helper.assertTrue(helper.getBlockEntity(localRight) == null,
                    "bottom-right must not own a block entity");
            helper.assertTrue(helper.getBlockEntity(upper) == null,
                    "top-left must not own a block entity");
            helper.assertTrue(helper.getBlockEntity(upperRight) == null,
                    "top-right must not own a block entity");
            helper.succeed();
        });
    }
}
