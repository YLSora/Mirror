package com.mirror.client;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Per-reflection-view temporal state used by shader-pack reprojection uniforms.
 *
 * <p>Reprojection is only valid when the previous snapshot is exactly one outer frame old. When a
 * view is deferred by the reflection budget, rebuilt after a layout change, or alternates with other
 * views sharing a pipeline slot, its last commit may be two or more frames stale; handing that
 * history to the shader as a one-frame reprojection vector causes a visible jump. The continuity
 * guard below returns the current matrices instead whenever the last commit was not the immediately
 * preceding frame.</p>
 */
final class MirrorViewHistory {
    private static long currentFrame;

    private Matrix4f modelView;
    private Matrix4f projection;
    private Vec3 cameraPosition;
    private long committedFrame = Long.MIN_VALUE;
    private boolean valid;

    /** Advances the shared outer-frame clock once per consumed reflection batch. */
    static void beginFrame() {
        currentFrame++;
    }

    Snapshot previousOr(Matrix4f currentModelView, Matrix4f currentProjection, Vec3 currentCameraPosition) {
        boolean continuous = valid && committedFrame == currentFrame - 1;
        if (!continuous) {
            return new Snapshot(new Matrix4f(currentModelView), new Matrix4f(currentProjection), currentCameraPosition);
        }
        return new Snapshot(new Matrix4f(modelView), new Matrix4f(projection), cameraPosition);
    }

    void commit(Matrix4f currentModelView, Matrix4f currentProjection, Vec3 currentCameraPosition) {
        modelView = new Matrix4f(currentModelView);
        projection = new Matrix4f(currentProjection);
        cameraPosition = currentCameraPosition;
        committedFrame = currentFrame;
        valid = true;
    }

    void reset() {
        modelView = null;
        projection = null;
        cameraPosition = null;
        committedFrame = Long.MIN_VALUE;
        valid = false;
    }

    record Snapshot(Matrix4f modelView, Matrix4f projection, Vec3 cameraPosition) {
    }
}
