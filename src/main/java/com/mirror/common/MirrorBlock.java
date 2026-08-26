package com.mirror.common;

import com.mirror.MirrorMod;
import com.mirror.config.MirrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

public final class MirrorBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final EnumProperty<ConnectionType> CONNECTION = ConnectionType.PROPERTY;
    public static final BooleanProperty FAR = BooleanProperty.create("far");
    public static final double FAR_RECESSION = 14.0 / 16.0;

    private static final Map<Direction, VoxelShape> NEAR_SHAPES = createShapes(0, 2);
    private static final Map<Direction, VoxelShape> FAR_SHAPES = createShapes(14, 16);

    public MirrorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(FAR, false)
                .setValue(CONNECTION, ConnectionType.SINGLE));
    }

    private static Map<Direction, VoxelShape> createShapes(int near, int far) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.NORTH, Block.box(0, 0, near, 16, 16, far));
        shapes.put(Direction.EAST, Block.box(16 - far, 0, 0, 16 - near, 16, 16));
        shapes.put(Direction.SOUTH, Block.box(0, 0, 16 - far, 16, 16, 16 - near));
        shapes.put(Direction.WEST, Block.box(near, 0, 0, far, 16, 16));
        return shapes;
    }

    public static double surfaceRecession(BlockState state) {
        return state.getValue(FAR) ? FAR_RECESSION : 0.0;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (state.getValue(FAR) ? FAR_SHAPES : NEAR_SHAPES).get(state.getValue(FACING));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FAR, CONNECTION);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockState state = defaultBlockState()
                .setValue(FACING, facing)
                .setValue(FAR, shouldPlaceFar(context, facing));
        // Seed the state from the already placed neighbours. setPlacedBy still performs the
        // complete rectangular rebuild, but doing this here keeps the new cell's model correct
        // even before the server sends the final group states back to the client.
        return state.setValue(CONNECTION, connectionTypeFromNeighbors(context.getLevel(),
                context.getClickedPos(), state));
    }

    private ConnectionType connectionTypeFromNeighbors(Level level, BlockPos pos, BlockState state) {
        if (maxConnectedSize() <= 1) return ConnectionType.SINGLE;
        Direction facing = state.getValue(FACING);
        boolean up = isConnectedNeighbor(level, pos, state, Direction.UP);
        boolean down = isConnectedNeighbor(level, pos, state, Direction.DOWN);
        boolean left = isConnectedNeighbor(level, pos, state, facing.getClockWise());
        boolean right = isConnectedNeighbor(level, pos, state, facing.getCounterClockWise());
        return ConnectionType.fromConnections(up, down, left, right);
    }

    private boolean isConnectedNeighbor(Level level, BlockPos pos, BlockState state, Direction side) {
        BlockState neighbor = level.getBlockState(pos.relative(side));
        if (!connectionMatches(state, neighbor)) return false;
        return neighbor.getValue(CONNECTION).isConnected(side.getOpposite(), state.getValue(FACING));
    }

    private static boolean shouldPlaceFar(BlockPlaceContext context, Direction facing) {
        return switch (MirrorConfig.COMMON.placementMode.get()) {
            case NEAR -> false;
            case FAR -> true;
            case BOTH -> isFarHalf(context, facing);
        };
    }

    private static boolean isFarHalf(BlockPlaceContext context, Direction facing) {
        Vec3 hit = context.getClickLocation();
        BlockPos pos = context.getClickedPos();
        double fraction = facing.getAxis() == Direction.Axis.X
                ? hit.x - pos.getX()
                : hit.z - pos.getZ();
        double towardViewer = (fraction - 0.5) * facing.getAxisDirection().getStep();
        return towardViewer < 0;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // A connected mirror has one owner: the rectangle's bottom-left cell.  Returning null
        // here is important; creating a tile for every cell makes BlockEntityRenderDispatcher
        // submit the same screen once per block and also makes a non-master tile look like a
        // valid owner to the renderer.
        return isMasterState(state) ? new MirrorBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                    BlockEntityType<T> type) {
        if (level.isClientSide || type != MirrorMod.MIRROR_BLOCK_ENTITY.get()) return null;
        return (tickerLevel, tickerPos, tickerState, blockEntity) ->
                MirrorBlockEntity.serverTick(tickerLevel, tickerPos, tickerState,
                        (MirrorBlockEntity) blockEntity);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(placer instanceof Player player) || !player.isSecondaryUseActive()) {
            MirrorGrid.rebuildAround(level, pos);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (level.isClientSide || level.getBlockState(pos).getBlock() != this) return;

        // Addition paths that do not call setPlacedBy (commands, structures and GameTest) still
        // need to connect. Removal is handled once by the removed block's onRemove; rebuilding
        // from every surviving neighbour here would process the same component repeatedly.
        BlockState changedNeighbour = level.getBlockState(fromPos);
        if (connectionMatches(state, changedNeighbour)) {
            MirrorGrid.rebuildAround(level, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                         boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            CompoundTag removedOwner = level.getBlockEntity(pos) instanceof MirrorBlockEntity entity
                    ? entity.saveWithoutMetadata() : null;
            MirrorGrid.rebuildAfterRemoval(level, pos, state, removedOwner);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    public Direction getStateFacing(Level level, BlockPos pos) {
        return level.getBlockState(pos).getValue(FACING);
    }

    public boolean connectionMatches(BlockState self, BlockState other) {
        return other.is(this)
                && other.getValue(FACING) == self.getValue(FACING)
                && other.getValue(FAR) == self.getValue(FAR);
    }

    public int maxConnectedSize() {
        return MirrorConfig.COMMON.maxConnectedSize.get();
    }

    public boolean squareAspectRatio() {
        return MirrorConfig.COMMON.squareAspectRatio.get();
    }

    @Nullable
    public static MirrorBlockEntity getMasterBlockEntity(Level level, BlockPos pos) {
        BlockPos masterPos = getMasterBlockPos(level, pos);
        return masterPos == null || !(level.getBlockEntity(masterPos) instanceof MirrorBlockEntity entity)
                ? null : entity;
    }

    /** Returns the rectangle owner, even while its block entity is being migrated. */
    @Nullable
    public static BlockPos getMasterBlockPos(Level level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof MirrorBlock mirror)) return null;
        BlockState reference = level.getBlockState(pos);
        Direction facing = reference.getValue(FACING);
        BlockPos current = pos;
        while (isSameCell(level.getBlockState(current), mirror, reference)
                && level.getBlockState(current).getValue(CONNECTION).isConnected(Direction.DOWN, facing)) {
            current = current.below();
        }
        while (isSameCell(level.getBlockState(current), mirror, reference)
                && level.getBlockState(current).getValue(CONNECTION)
                .isConnected(facing.getClockWise(), facing)) {
            current = current.relative(facing.getClockWise());
        }
        return isSameCell(level.getBlockState(current), mirror, reference)
                && isMasterState(level.getBlockState(current)) ? current : null;
    }

    public static boolean isMasterState(BlockState state) {
        if (!(state.getBlock() instanceof MirrorBlock)) return false;
        return isMasterConnection(state.getValue(FACING), state.getValue(CONNECTION));
    }

    public static boolean isMasterConnection(Direction facing, ConnectionType connection) {
        return connection.isMaster(facing);
    }

    private static boolean isSameCell(BlockState state, MirrorBlock mirror, BlockState reference) {
        return mirror.connectionMatches(reference, state);
    }
}
