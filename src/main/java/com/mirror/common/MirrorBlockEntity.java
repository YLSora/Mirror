package com.mirror.common;

import com.mirror.MirrorMod;
import com.mirror.common.enderman.MirrorEndermanObservationController;
import com.mirror.config.MirrorConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public final class MirrorBlockEntity extends BlockEntity {
    public static final int FRAME_PIXELS = 2;

    private UUID id = UUID.randomUUID();
    private int connectedWidth = 1;
    private int connectedHeight = 1;
    private final MirrorEndermanObservationController observationController =
            new MirrorEndermanObservationController(this);

    public MirrorBlockEntity(BlockPos pos, BlockState state) {
        super(MirrorMod.MIRROR_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getId() {
        return id;
    }

    public int getConnectedWidth() {
        return connectedWidth;
    }

    public int getConnectedHeight() {
        return connectedHeight;
    }

    public void setConnectionSize(int width, int height) {
        connectedWidth = Math.max(1, width);
        connectedHeight = Math.max(1, height);
        setChanged();
    }

    public int getScreenPixelWidth() {
        return connectedWidth * 16 - FRAME_PIXELS;
    }

    public int getScreenPixelHeight() {
        return connectedHeight * 16 - FRAME_PIXELS;
    }

    public ScreenRect getScreenRect() {
        Direction facing = getBlockState().getValue(MirrorBlock.FACING);
        Vec3 normal = Vec3.atLowerCornerOf(facing.getNormal());
        double recession = MirrorBlock.surfaceRecession(getBlockState());
        Vec3 plane = Vec3.atCenterOf(worldPosition).add(normal.scale(0.5 - recession));
        Vec3 center = plane
                .add(ScreenRect.rightOf(normal).scale((1 - connectedWidth) * 0.5))
                .add(new Vec3(0, 1, 0).scale((connectedHeight - 1) * 0.5));
        return new ScreenRect(center, normal, connectedWidth, connectedHeight);
    }

    @Override
    public AABB getRenderBoundingBox() {
        AABB box = new AABB(worldPosition);
        Direction facing = getBlockState().getValue(MirrorBlock.FACING);
        AABB connectedBounds = switch (facing) {
            case NORTH -> box.expandTowards(-connectedWidth + 1, connectedHeight - 1, 0);
            case SOUTH -> box.expandTowards(connectedWidth - 1, connectedHeight - 1, 0);
            case EAST -> box.expandTowards(0, connectedHeight - 1, -connectedWidth + 1);
            case WEST -> box.expandTowards(0, connectedHeight - 1, connectedWidth - 1);
            default -> box;
        };
        return connectedBounds.inflate(1.0 / 16.0);
    }

    public double distanceToRenderBoundsSqr(Vec3 point) {
        AABB bounds = getRenderBoundingBox();
        double dx = Math.max(Math.max(bounds.minX - point.x, 0.0), point.x - bounds.maxX);
        double dy = Math.max(Math.max(bounds.minY - point.y, 0.0), point.y - bounds.maxY);
        double dz = Math.max(Math.max(bounds.minZ - point.z, 0.0), point.z - bounds.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MirrorBlockEntity mirror) {
        if (level.isClientSide || !MirrorConfig.COMMON.enableEndermanObservation.get()) return;
        if (MirrorBlock.getMasterBlockEntity(level, pos) != mirror) return;
        if ((level.getGameTime() + pos.asLong()) % 10 != 0) return;
        mirror.observationController.tick();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("id")) id = tag.getUUID("id");
        connectedWidth = Math.max(1, tag.getInt("ConnectionWidth"));
        connectedHeight = Math.max(1, tag.getInt("ConnectionHeight"));
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putUUID("id", id);
        tag.putInt("ConnectionWidth", connectedWidth);
        tag.putInt("ConnectionHeight", connectedHeight);
    }
}
