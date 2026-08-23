package com.mirror.common;

import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** A horizontal-facing rectangular surface in world space. */
public record ScreenRect(Vec3 center, Vec3 normal, float width, float height) {
    private static final Vec3 UP = new Vec3(0, 1, 0);

    public static Vec3 rightOf(Vec3 normal) {
        return UP.cross(normal);
    }

    public Vec3 right() {
        return rightOf(normal);
    }

    public Vec3 up() {
        return UP;
    }

    @Nullable
    public Vec2 projectLocal(Vec3 worldPoint) {
        Vec3 local = worldPoint.subtract(center);
        double x = local.dot(right());
        double y = local.dot(UP);
        if (Math.abs(x) > width / 2.0f || Math.abs(y) > height / 2.0f) {
            return null;
        }
        return new Vec2((float) (x / width), (float) (y / height));
    }

    public Vec3 localToWorld(Vec2 localHit) {
        return center.add(right().scale(localHit.x * width)).add(UP.scale(localHit.y * height));
    }
}
