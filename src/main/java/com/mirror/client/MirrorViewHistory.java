package com.mirror.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Per-reflection-view temporal state used by shader-pack reprojection uniforms. */
final class MirrorViewHistory {
    private Matrix4f modelView;
    private Matrix4f projection;
    private Vec3 cameraPosition;
    private boolean valid;

    Snapshot previousOr(Matrix4f currentModelView, Matrix4f currentProjection, Vec3 currentCameraPosition) {
        if (!valid) {
            return new Snapshot(new Matrix4f(currentModelView), new Matrix4f(currentProjection), currentCameraPosition);
        }
        return new Snapshot(new Matrix4f(modelView), new Matrix4f(projection), cameraPosition);
    }

    void commit(Matrix4f currentModelView, Matrix4f currentProjection, Vec3 currentCameraPosition) {
        modelView = new Matrix4f(currentModelView);
        projection = new Matrix4f(currentProjection);
        cameraPosition = currentCameraPosition;
        valid = true;
    }

    void reset() {
        modelView = null;
        projection = null;
        cameraPosition = null;
        valid = false;
    }

    record Snapshot(Matrix4f modelView, Matrix4f projection, Vec3 cameraPosition) {
    }
}
