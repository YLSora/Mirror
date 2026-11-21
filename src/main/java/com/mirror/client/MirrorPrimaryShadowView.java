package com.mirror.client;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/** Last shadow view actually rendered by the primary pipeline. */
public final class MirrorPrimaryShadowView {
    private static volatile Snapshot snapshot;

    private MirrorPrimaryShadowView() {
    }

    public static void record(Object owner, Matrix4f modelView, Vector3d cameraPosition) {
        snapshot = new Snapshot(owner, new Matrix4f(modelView), new Vector3d(cameraPosition));
    }

    public static Matrix4f forCamera(Object owner, Vector3d mirrorCamera) {
        Snapshot current = snapshot;
        return current == null || current.owner != owner ? null
                : MirrorShadowSharing.shadowModelView(current.modelView, mirrorCamera, current.cameraPosition);
    }

    private record Snapshot(Object owner, Matrix4f modelView, Vector3d cameraPosition) {
    }
}
