package com.mirror.client;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorShadowSharingTest {
    @Test
    void restoresFlipSnapshotAfterMutation() {
        boolean[] flipped = {true, false, true};
        boolean[] snapshot = MirrorShadowSharing.snapshotFlipped(flipped);
        flipped[0] = false;
        flipped[1] = true;
        MirrorShadowSharing.restoreFlipped(flipped, snapshot);
        assertArrayEquals(new boolean[]{true, false, true}, flipped);
    }

    @Test
    void detachesOnlyFramebufferInstancesAddedDuringBuild() {
        Object primaryFramebuffer = new String("same id");
        Object mirrorFramebuffer = new String("same id");
        List<Object> shared = new ArrayList<>(List.of(primaryFramebuffer, mirrorFramebuffer));

        List<Object> detached = MirrorShadowSharing.detachAdded(
                shared, MirrorShadowSharing.identitySnapshot(List.of(primaryFramebuffer)));

        assertEquals(1, shared.size());
        assertSame(primaryFramebuffer, shared.get(0));
        assertEquals(1, detached.size());
        assertSame(mirrorFramebuffer, detached.get(0));
    }

    @Test
    void checksEveryBorrowingPreconditionAndResolution() {
        assertNull(MirrorShadowSharing.borrowFailure(true, true, true, true, true, true, true,
                true, 2048, 2048, 2048));
        assertTrue(MirrorShadowSharing.borrowFailure(true, true, true, true, true, true, true,
                true, 2048, 2048, 1024).contains("resolution mismatch"));
        assertEquals("disabled by config", MirrorShadowSharing.borrowFailure(false, true, true, true, true,
                true, true, true, 2048, 2048, 2048));
    }

    @Test
    void shiftsPrimaryShadowViewByDoublePrecisionCameraDelta() {
        Matrix4f primary = new Matrix4f().rotationY(0.4f).translate(4.0f, 5.0f, 6.0f);
        Vector3d primaryCamera = new Vector3d(100_000_000.0, -100_000_000.0, 40.0);
        Vector3d mirrorCamera = new Vector3d(100_000_000.75, -99_999_998.5, 36.25);

        Matrix4f actual = MirrorShadowSharing.shadowModelView(primary, mirrorCamera, primaryCamera);

        assertEquals(new Matrix4f(primary).translate(0.75f, 1.5f, -3.75f), actual);
    }
    @Test
    void checksCustomTextureSharingPreconditionsIndependently() {
        assertNull(MirrorShadowSharing.customTextureBorrowFailure(true, true, true, true, true,
                64, 64));
        assertEquals("disabled by config", MirrorShadowSharing.customTextureBorrowFailure(false,
                true, true, true, true, 64, 64));
        assertEquals("primary pipeline is not IrisRenderingPipeline",
                MirrorShadowSharing.customTextureBorrowFailure(true, false, true, true, true, 64, 64));
        assertEquals("custom texture safety mixin unavailable",
                MirrorShadowSharing.customTextureBorrowFailure(true, true, false, true, true, 64, 64));
        assertEquals("primary CustomTextureManager unavailable",
                MirrorShadowSharing.customTextureBorrowFailure(true, true, true, false, true, 64, 64));
        assertEquals("shader pack mismatch",
                MirrorShadowSharing.customTextureBorrowFailure(true, true, true, true, false, 64, 64));
        assertTrue(MirrorShadowSharing.customTextureBorrowFailure(true, true, true, true, true,
                64, 32).contains("noise texture resolution mismatch"));
    }

}
