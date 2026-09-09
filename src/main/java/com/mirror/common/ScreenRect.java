package com.mirror.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** A rectangular mirror surface with fixed local axes in world space. */
public record ScreenRect(Vec3 center, MirrorOrientation orientation, float width, float height) {
    public static ScreenRect fromMaster(BlockPos master, Direction facing, int width, int height,
                                        double recession) {
        MirrorOrientation orientation = MirrorOrientation.of(facing);
        Vec3 center = Vec3.atCenterOf(master)
                .add(orientation.normal().scale(0.5 - recession))
                .add(orientation.right().scale((width - 1) * 0.5))
                .add(orientation.up().scale((height - 1) * 0.5));
        return new ScreenRect(center, orientation, width, height);
    }

    public Vec3 normal() {
        return orientation.normal();
    }

    public Vec3 right() {
        return orientation.right();
    }

    public Vec3 up() {
        return orientation.up();
    }

    @Nullable
    public Vec2 projectLocal(Vec3 worldPoint) {
        Vec3 local = worldPoint.subtract(center);
        double x = local.dot(right());
        double y = local.dot(up());
        if (Math.abs(x) > width / 2.0f || Math.abs(y) > height / 2.0f) {
            return null;
        }
        return new Vec2((float) (x / width), (float) (y / height));
    }

    public Vec3 localToWorld(Vec2 localHit) {
        return center.add(right().scale(localHit.x * width)).add(up().scale(localHit.y * height));
    }
}
