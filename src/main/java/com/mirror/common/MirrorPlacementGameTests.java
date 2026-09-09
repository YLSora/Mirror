package com.mirror.common;

import com.mirror.MirrorMod;
import com.mirror.config.MirrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Exercises placement through ItemStack.useOn, including Forge's placement notifications. */
@GameTestHolder(MirrorMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MirrorPlacementGameTests {
    private static final String TEMPLATE = "mirrorgridgametests.empty";
    private static final BlockPos SUPPORT = new BlockPos(2, 2, 2);

    private MirrorPlacementGameTests() {
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void normalPlacementFacesPlayerOnEveryFace(GameTestHelper helper) {
        helper.setBlock(SUPPORT, Blocks.STONE);
        for (MirrorConfig.PlacementMode mode : MirrorConfig.PlacementMode.values()) {
            withPlacementMode(mode, () -> {
                for (Direction look : Direction.Plane.HORIZONTAL) {
                    Player player = player(helper, look, false);
                    for (float pitch : new float[]{-75, 0, 75}) {
                        player.setXRot(pitch);
                        for (Direction face : Direction.values()) {
                            BlockState placed = place(helper, player, hit(helper, SUPPORT, face));
                            helper.assertTrue(placed.getValue(MirrorBlock.FACING) == look.getOpposite(),
                                    "normal placement must stay vertical regardless of pitch or clicked face");
                            if (mode != MirrorConfig.PlacementMode.BOTH) {
                                helper.assertTrue(placed.getValue(MirrorBlock.FAR) == (mode == MirrorConfig.PlacementMode.FAR),
                                        "vertical placement must respect the configured position");
                            }
                            helper.setBlock(SUPPORT.relative(face), Blocks.AIR);
                        }
                    }
                }
            });
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void sneakingPlacementFacesPlayerRegardlessOfClickedFace(GameTestHelper helper) {
        helper.setBlock(SUPPORT, Blocks.STONE);
        for (MirrorConfig.PlacementMode mode : MirrorConfig.PlacementMode.values()) {
            withPlacementMode(mode, () -> {
                for (Direction look : Direction.values()) {
                    Player player = player(helper, look, true);
                    for (Direction face : Direction.values()) {
                        BlockPos target = SUPPORT.relative(face);
                        BlockState placed = place(helper, player, hit(helper, SUPPORT, face));
                        helper.assertTrue(placed.getValue(MirrorBlock.FACING) == look.getOpposite(),
                                "sneaking placement must face the player independently of the clicked face");
                        if (mode != MirrorConfig.PlacementMode.BOTH) {
                            helper.assertTrue(placed.getValue(MirrorBlock.FAR) == (mode == MirrorConfig.PlacementMode.FAR),
                                    "position config must apply to horizontal and vertical mirrors");
                        }
                        helper.setBlock(target, Blocks.AIR);
                    }
                }
            });
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void horizontalBothModeSelectsHeightWithoutChangingFacing(GameTestHelper helper) {
        withPlacementMode(MirrorConfig.PlacementMode.BOTH, () -> {
            helper.setBlock(SUPPORT, Blocks.STONE);
            for (Direction facing : new Direction[]{Direction.UP, Direction.DOWN}) {
                Player player = player(helper, facing.getOpposite(), true);
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos target = SUPPORT.relative(side);
                    for (boolean top : new boolean[]{false, true}) {
                        BlockHitResult centerHit = hit(helper, SUPPORT, side);
                        Vec3 location = centerHit.getLocation().add(0, top ? 0.25 : -0.25, 0);
                        BlockState placed = place(helper, player,
                                new BlockHitResult(location, side, centerHit.getBlockPos(), false));
                        helper.assertTrue(placed.getValue(MirrorBlock.FACING) == facing,
                                "changing the clicked height must not change the horizontal reflection direction");
                        AABB shape = placed.getShape(helper.getLevel(), helper.absolutePos(target)).bounds();
                        double minY = top ? 0.875 : 0;
                        helper.assertTrue(shape.minY == minY && shape.maxY == minY + 0.125
                                        && shape.getXsize() == 1 && shape.getZsize() == 1,
                                "side clicks must place the horizontal mirror in the selected top or bottom half");
                        MirrorBlockEntity owner = (MirrorBlockEntity) helper.getBlockEntity(target);
                        ScreenRect screen = ScreenRect.fromMaster(helper.absolutePos(target), facing,
                                owner.getConnectedWidth(), owner.getConnectedHeight(), MirrorBlock.surfaceRecession(placed));
                        double surfaceY = helper.absolutePos(target).getY()
                                + (facing == Direction.UP ? shape.maxY : shape.minY);
                        helper.assertTrue(screen.center().y == surfaceY,
                                "reflection plane must match the exposed side of either horizontal position");
                        helper.setBlock(target, Blocks.AIR);
                    }
                }
            }
        });
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void thinEdgesInheritFacingAndDepthInEveryOrientation(GameTestHelper helper) {
        for (Direction facing : Direction.values()) {
            for (boolean far : new boolean[]{false, true}) {
                withPlacementMode(far ? MirrorConfig.PlacementMode.NEAR : MirrorConfig.PlacementMode.FAR, () -> {
                    Player player = player(helper, differentLook(facing), false);
                    for (ConnectionType.LocalSide side : ConnectionType.LocalSide.values()) {
                        Direction edge = MirrorOrientation.of(facing).direction(side);
                        helper.setBlock(SUPPORT, mirror(facing, far));
                        BlockPos target = SUPPORT.relative(edge);
                        BlockState placed = place(helper, player, hit(helper, SUPPORT, edge));
                        helper.assertTrue(placed.getValue(MirrorBlock.FACING) == facing
                                        && placed.getValue(MirrorBlock.FAR) == far,
                                "thin-edge placement must inherit facing and depth instead of player or config");
                        assertJoinedPair(helper, SUPPORT, target);
                        helper.setBlock(target, Blocks.AIR);
                        helper.setBlock(SUPPORT, Blocks.AIR);
                    }
                });
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void frontAndBackUseOrdinaryAdjacentPlacement(GameTestHelper helper) {
        withPlacementMode(MirrorConfig.PlacementMode.NEAR, () -> {
            for (Direction facing : Direction.values()) {
                for (boolean far : new boolean[]{false, true}) {
                    Player player = player(helper, differentLook(facing), false);
                    for (Direction face : new Direction[]{facing, facing.getOpposite()}) {
                        helper.setBlock(SUPPORT, mirror(facing, far));
                        BlockPos target = SUPPORT.relative(face);
                        BlockState placed = place(helper, player, hit(helper, SUPPORT, face));
                        helper.assertTrue(placed.getValue(MirrorBlock.FACING) == player.getDirection().getOpposite()
                                        && !placed.getValue(MirrorBlock.FAR),
                                "front and back clicks must use ordinary vertical placement");
                        helper.assertBlockProperty(target, MirrorBlock.CONNECTION, ConnectionType.SINGLE);
                        helper.assertBlockProperty(SUPPORT, MirrorBlock.FACING, facing);
                        helper.assertBlockProperty(SUPPORT, MirrorBlock.FAR, far);
                        helper.setBlock(target, Blocks.AIR);
                        helper.setBlock(SUPPORT, Blocks.AIR);
                    }
                }
            }
        });
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void sneakingOnMirrorsBypassesInheritanceOnEveryFace(GameTestHelper helper) {
        withPlacementMode(MirrorConfig.PlacementMode.NEAR, () -> {
            for (Direction facing : Direction.values()) {
                for (boolean far : new boolean[]{false, true}) {
                    for (Direction look : Direction.values()) {
                        Player player = player(helper, look, true);
                        for (Direction face : Direction.values()) {
                            helper.setBlock(SUPPORT, mirror(facing, far));
                            BlockState placed = place(helper, player, hit(helper, SUPPORT, face));
                            helper.assertTrue(placed.getValue(MirrorBlock.FACING) == look.getOpposite()
                                            && !placed.getValue(MirrorBlock.FAR),
                                    "sneaking on a mirror must use player direction and configured position, without inheritance");
                            helper.assertBlockProperty(SUPPORT, MirrorBlock.FACING, facing);
                            helper.assertBlockProperty(SUPPORT, MirrorBlock.FAR, far);
                            helper.setBlock(SUPPORT.relative(face), Blocks.AIR);
                            helper.setBlock(SUPPORT, Blocks.AIR);
                        }
                    }
                }
            }
        });
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void sneakingMatchingMirrorsStillConnect(GameTestHelper helper) {
        for (boolean far : new boolean[]{false, true}) {
            withPlacementMode(far ? MirrorConfig.PlacementMode.FAR : MirrorConfig.PlacementMode.NEAR, () -> {
                helper.setBlock(SUPPORT, mirror(Direction.NORTH, far));
                Player player = player(helper, Direction.SOUTH, true);
                place(helper, player, hit(helper, SUPPORT, Direction.WEST));
                assertJoinedPair(helper, SUPPORT, SUPPORT.west());
                helper.setBlock(SUPPORT.west(), Blocks.AIR);
                helper.setBlock(SUPPORT, Blocks.AIR);
            });
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void replacingClickedBlockDoesNotInheritMirrorBehindIt(GameTestHelper helper) {
        withPlacementMode(MirrorConfig.PlacementMode.NEAR, () -> {
            helper.setBlock(SUPPORT, mirror(Direction.NORTH, true));
            BlockPos target = SUPPORT.above();
            helper.setBlock(target, Blocks.GRASS);
            Player player = player(helper, Direction.WEST, false);
            BlockState placed = place(helper, player, hit(helper, target, Direction.UP));
            helper.assertTrue(placed.getValue(MirrorBlock.FACING) == Direction.EAST
                            && !placed.getValue(MirrorBlock.FAR),
                    "replacing a clicked block must not inherit from the mirror behind that block");
            helper.assertBlockPresent(MirrorMod.MIRROR.get(), target);
            helper.assertBlockPresent(Blocks.AIR, target.above());
        });
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void verticalBothModeUsesTheClickedHalf(GameTestHelper helper) {
        withPlacementMode(MirrorConfig.PlacementMode.BOTH, () -> {
            helper.setBlock(SUPPORT, Blocks.STONE);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                Player player = player(helper, facing.getOpposite(), false);
                for (boolean far : new boolean[]{false, true}) {
                    BlockHitResult centerHit = hit(helper, SUPPORT, Direction.UP);
                    Vec3 location = centerHit.getLocation()
                            .add(Vec3.atLowerCornerOf(facing.getNormal()).scale(far ? -0.25 : 0.25));
                    BlockState placed = place(helper, player,
                            new BlockHitResult(location, Direction.UP, centerHit.getBlockPos(), false));
                    helper.assertTrue(placed.getValue(MirrorBlock.FAR) == far,
                            "BOTH must choose the clicked half along the vertical mirror's normal");
                    helper.setBlock(SUPPORT.above(), Blocks.AIR);
                }
            }
        });
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "minecraft")
    public static void blockedAndRestrictedPlacementDoesNotConsumeItems(GameTestHelper helper) {
        helper.setBlock(SUPPORT, Blocks.STONE);
        BlockPos target = SUPPORT.above();
        for (boolean sneaking : new boolean[]{false, true}) {
            Player player = player(helper, Direction.SOUTH, sneaking);
            helper.setBlock(target, Blocks.STONE);
            assertFailedPlacement(helper, player, hit(helper, SUPPORT, Direction.UP));
            helper.assertBlockPresent(Blocks.STONE, target);
            helper.setBlock(target, Blocks.AIR);
            player.getAbilities().mayBuild = false;
            assertFailedPlacement(helper, player, hit(helper, SUPPORT, Direction.UP));
            helper.assertBlockPresent(Blocks.AIR, target);
        }
        Player player = player(helper, Direction.DOWN, true);
        ArmorStand obstruction = helper.spawn(EntityType.ARMOR_STAND, target);
        obstruction.blocksBuilding = true;
        assertFailedPlacement(helper, player, hit(helper, SUPPORT, Direction.UP));
        helper.assertBlockPresent(Blocks.AIR, target);
        obstruction.discard();
        player.getAbilities().instabuild = true;
        place(helper, player, hit(helper, SUPPORT, Direction.UP));
        helper.succeed();
    }

    private static Player player(GameTestHelper helper, Direction look, boolean sneaking) {
        Player player = helper.makeMockPlayer();
        player.setYRot(look.getAxis().isHorizontal() ? look.toYRot() : 0);
        player.setYHeadRot(player.getYRot());
        player.setXRot(-75 * look.getStepY());
        player.setShiftKeyDown(sneaking);
        player.getAbilities().instabuild = false;
        return player;
    }

    private static Direction differentLook(Direction facing) {
        return facing.getAxis() == Direction.Axis.X ? Direction.SOUTH : Direction.WEST;
    }

    private static BlockState mirror(Direction facing, boolean far) {
        return MirrorMod.MIRROR.get().defaultBlockState()
                .setValue(MirrorBlock.FACING, facing).setValue(MirrorBlock.FAR, far);
    }

    private static BlockHitResult hit(GameTestHelper helper, BlockPos pos, Direction face) {
        BlockPos absolute = helper.absolutePos(pos);
        AABB bounds = helper.getBlockState(pos).getShape(helper.getLevel(), absolute).bounds();
        Vec3 center = bounds.getCenter();
        Vec3 location = center.add(face.getStepX() * bounds.getXsize() / 2,
                face.getStepY() * bounds.getYsize() / 2, face.getStepZ() * bounds.getZsize() / 2)
                .add(Vec3.atLowerCornerOf(absolute));
        return new BlockHitResult(location, face, absolute, false);
    }

    private static BlockState place(GameTestHelper helper, Player player, BlockHitResult hit) {
        ItemStack stack = new ItemStack(MirrorMod.MIRROR_ITEM.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        UseOnContext context = new UseOnContext(player, InteractionHand.MAIN_HAND, hit);
        BlockPos target = new BlockPlaceContext(context).getClickedPos();
        InteractionResult result = stack.useOn(context);
        helper.assertTrue(result.consumesAction(), "mirror item placement must succeed");
        helper.assertTrue(stack.getCount() == (player.getAbilities().instabuild ? 2 : 1),
                "successful placement must consume one item in survival and none in creative");
        BlockState placed = helper.getLevel().getBlockState(target);
        helper.assertTrue(placed.is(MirrorMod.MIRROR.get()), "mirror must occupy the vanilla destination cell");
        return placed;
    }

    private static void assertFailedPlacement(GameTestHelper helper, Player player, BlockHitResult hit) {
        ItemStack stack = new ItemStack(MirrorMod.MIRROR_ITEM.get(), 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(!result.consumesAction() && stack.getCount() == 2,
                "blocked placement must fail without consuming a mirror");
    }

    private static void assertJoinedPair(GameTestHelper helper, BlockPos first, BlockPos second) {
        MirrorBlockEntity owner = MirrorBlock.getMasterBlockEntity(helper.getLevel(), helper.absolutePos(first));
        helper.assertTrue(owner != null
                        && MirrorBlock.getMasterBlockEntity(helper.getLevel(), helper.absolutePos(second)) == owner
                        && owner.getConnectedWidth() * owner.getConnectedHeight() == 2,
                "coplanar matching mirrors must form one two-cell reflection surface");
        helper.assertTrue((helper.getBlockEntity(first) != null) != (helper.getBlockEntity(second) != null),
                "a connected pair must have exactly one owner");
    }

    private static void withPlacementMode(MirrorConfig.PlacementMode mode, Runnable action) {
        MirrorConfig.PlacementMode original = MirrorConfig.COMMON.placementMode.get();
        try {
            MirrorConfig.COMMON.placementMode.set(mode);
            action.run();
        } finally {
            MirrorConfig.COMMON.placementMode.set(original);
        }
    }
}
