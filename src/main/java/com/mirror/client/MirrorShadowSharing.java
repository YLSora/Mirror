package com.mirror.client;

import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Pure bookkeeping and coordinate math for borrowing the primary shadow map. */
public final class MirrorShadowSharing {
    private MirrorShadowSharing() {
    }

    public static String borrowFailure(boolean enabled, boolean primaryIsIris, boolean safetyAvailable,
                                       boolean targetsAvailable, boolean targetAccessAvailable,
                                       boolean rendererAvailable, boolean bufferAccessAvailable,
                                       boolean rendererBuildAvailable,
                                       int primaryResolution, int targetResolution, int mirrorResolution) {
        if (!enabled) return "disabled by config";
        if (!primaryIsIris) return "primary pipeline is not IrisRenderingPipeline";
        if (!safetyAvailable) return "shadow safety mixin unavailable";
        if (!targetsAvailable) return "primary shadow targets unavailable";
        if (!targetAccessAvailable) return "shadow-target access unavailable";
        if (!rendererAvailable) return "primary ShadowRenderer unavailable";
        if (!bufferAccessAvailable) return "RenderBuffers access unavailable";
        if (!rendererBuildAvailable) return "ShadowRenderer construction hook unavailable";
        if (primaryResolution != targetResolution || targetResolution != mirrorResolution) {
            return "shadow resolution mismatch (primary " + targetResolution + ", mirror " + mirrorResolution + ")";
        }
        return null;
    }

    public static String customTextureBorrowFailure(boolean enabled, boolean primaryIsIris,
                                                    boolean safetyAvailable, boolean managerAvailable,
                                                    boolean samePack, int primaryNoiseResolution,
                                                    int mirrorNoiseResolution) {
        if (!enabled) return "disabled by config";
        if (!primaryIsIris) return "primary pipeline is not IrisRenderingPipeline";
        if (!safetyAvailable) return "custom texture safety mixin unavailable";
        if (!managerAvailable) return "primary CustomTextureManager unavailable";
        if (!samePack) return "shader pack mismatch";
        if (primaryNoiseResolution != mirrorNoiseResolution) {
            return "noise texture resolution mismatch (primary " + primaryNoiseResolution
                    + ", mirror " + mirrorNoiseResolution + ")";
        }
        return null;
    }

    public static boolean[] snapshotFlipped(boolean[] flipped) {
        return flipped.clone();
    }

    public static void restoreFlipped(boolean[] flipped, boolean[] snapshot) {
        if (flipped.length != snapshot.length) throw new IllegalArgumentException("flip array size changed during build");
        System.arraycopy(snapshot, 0, flipped, 0, flipped.length);
    }

    public static <T> Set<T> identitySnapshot(List<T> values) {
        Set<T> snapshot = Collections.newSetFromMap(new IdentityHashMap<>());
        snapshot.addAll(values);
        return snapshot;
    }

    public static <T> List<T> detachAdded(List<T> values, Set<T> before) {
        List<T> added = new ArrayList<>();
        for (Iterator<T> iterator = values.iterator(); iterator.hasNext();) {
            T value = iterator.next();
            if (!before.contains(value)) {
                added.add(value);
                iterator.remove();
            }
        }
        return added;
    }

    public static Matrix4f shadowModelView(Matrix4f primaryModelView, Vector3d mirrorCamera,
                                           Vector3d primaryCamera) {
        double x = mirrorCamera.x - primaryCamera.x;
        double y = mirrorCamera.y - primaryCamera.y;
        double z = mirrorCamera.z - primaryCamera.z;
        return new Matrix4f(primaryModelView).translate((float) x, (float) y, (float) z);
    }
}
