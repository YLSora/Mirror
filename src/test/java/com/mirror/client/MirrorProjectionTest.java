package com.mirror.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MirrorProjectionTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void centeredApertureWithMatchingAspectNeedsNoCrop() {
        MirrorProjection.ViewportProjection fitted =
                new MirrorProjection(-2.0f, 2.0f, -1.0f, 1.0f, 1.0f)
                        .fitViewport(200, 100);

        assertEquals(-2.0f, fitted.left(), EPSILON);
        assertEquals(2.0f, fitted.right(), EPSILON);
        assertEquals(-1.0f, fitted.bottom(), EPSILON);
        assertEquals(1.0f, fitted.top(), EPSILON);
        assertEquals(MirrorProjection.UvRect.full(), fitted.crop());
    }

    @Test
    void offAxisApertureIsContainedByCenteredCapture() {
        MirrorProjection.ViewportProjection fitted =
                new MirrorProjection(1.0f, 3.0f, -1.0f, 1.0f, 1.0f)
                        .fitViewport(200, 100);

        assertEquals(-3.0f, fitted.left(), EPSILON);
        assertEquals(3.0f, fitted.right(), EPSILON);
        assertEquals(-1.5f, fitted.bottom(), EPSILON);
        assertEquals(1.5f, fitted.top(), EPSILON);
        assertEquals(2.0f / 3.0f, fitted.crop().minU(), EPSILON);
        assertEquals(1.0f, fitted.crop().maxU(), EPSILON);
        assertEquals(1.0f / 6.0f, fitted.crop().minV(), EPSILON);
        assertEquals(5.0f / 6.0f, fitted.crop().maxV(), EPSILON);
        assertEquals(5.0f / 6.0f, fitted.crop().centerU(), EPSILON);
        assertEquals(0.5f, fitted.crop().centerV(), EPSILON);
    }

    @Test
    void framebufferAspectExpandsOnlyAsMuchAsNeeded() {
        MirrorProjection.ViewportProjection fitted =
                new MirrorProjection(-2.0f, 2.0f, -1.0f, 1.0f, 1.0f)
                        .fitViewport(256, 256);

        assertEquals(-2.0f, fitted.left(), EPSILON);
        assertEquals(2.0f, fitted.right(), EPSILON);
        assertEquals(-2.0f, fitted.bottom(), EPSILON);
        assertEquals(2.0f, fitted.top(), EPSILON);
        assertEquals(0.25f, fitted.crop().minV(), EPSILON);
        assertEquals(0.75f, fitted.crop().maxV(), EPSILON);
    }
    @Test
    void stabilizerKeepsOffAxisApertureAwayFromFramebufferEdges() {
        MirrorProjectionStabilizer stabilizer = new MirrorProjectionStabilizer();
        MirrorProjection.ViewportProjection fitted = stabilizer.fit(
                new MirrorProjection(1.0f, 3.0f, -1.0f, 1.0f, 1.0f), 256, 256);

        // The physical aperture must no longer touch the capture edge. The exact margin is larger
        // than GUARD_BAND because the stabilizer also reserves expansion headroom.
        assertEquals(true, fitted.crop().maxU() < 1.0f - MirrorProjectionStabilizer.SHADER_GUARD_BAND);
        assertEquals(true, fitted.crop().minU() > MirrorProjectionStabilizer.SHADER_GUARD_BAND);
        assertEquals(true, fitted.crop().minV() > MirrorProjectionStabilizer.SHADER_GUARD_BAND);
        assertEquals(true, fitted.crop().maxV() < 1.0f - MirrorProjectionStabilizer.SHADER_GUARD_BAND);
    }

    @Test
    void stabilizerDoesNotChangeEnvelopeForSmallLateralMotion() {
        MirrorProjectionStabilizer stabilizer = new MirrorProjectionStabilizer();
        MirrorProjection.ViewportProjection first = stabilizer.fit(
                new MirrorProjection(1.0f, 3.0f, -1.0f, 1.0f, 1.0f), 256, 256);
        MirrorProjection.ViewportProjection second = stabilizer.fit(
                new MirrorProjection(1.02f, 3.02f, -1.0f, 1.0f, 1.0f), 256, 256);

        assertEquals(first.left(), second.left(), EPSILON);
        assertEquals(first.right(), second.right(), EPSILON);
        assertEquals(first.bottom(), second.bottom(), EPSILON);
        assertEquals(first.top(), second.top(), EPSILON);
    }

}
