package com.mirror.client;

import net.minecraft.world.phys.Vec3;

public record MirrorReflection(Vec3 reflectedEye, double signedDistance) {
    public static MirrorReflection compute(Vec3 planePoint, Vec3 planeNormal, Vec3 eye) {
        Vec3 delta = eye.subtract(planePoint);
        double distance = delta.dot(planeNormal);
        return new MirrorReflection(eye.subtract(planeNormal.scale(2.0 * distance)), distance);
    }

    public boolean viewerInFront() {
        return signedDistance > 0.0;
    }
}
