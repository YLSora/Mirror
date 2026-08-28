package com.mirror.client;

import org.joml.Matrix4f;

/**
 * Exact planar-mirror aperture and the shader-safe projection used to capture it.
 *
 * <p>The physical mirror is generally an off-axis aperture. Passing that projection directly to
 * arbitrary shader packs is fragile because many screen-space effects assume a centered
 * perspective matrix. {@link #fitViewport(int, int)} therefore builds the smallest centered,
 * symmetric frustum with the capture target's aspect ratio that fully contains the physical
 * aperture. The exact reflected rays are recovered by sampling only the returned UV crop.</p>
 */
public record MirrorProjection(float left, float right, float bottom, float top, float nearPlane) {
    public MirrorProjection {
        if (!Float.isFinite(left) || !Float.isFinite(right)
                || !Float.isFinite(bottom) || !Float.isFinite(top)
                || !Float.isFinite(nearPlane)) {
            throw new IllegalArgumentException("mirror projection values must be finite");
        }
        if (nearPlane <= 0.0f) throw new IllegalArgumentException("nearPlane must be positive");
        if (right <= left) throw new IllegalArgumentException("right must be greater than left");
        if (top <= bottom) throw new IllegalArgumentException("top must be greater than bottom");
    }

    /**
     * Returns a centered projection compatible with the supplied viewport and an exact crop for
     * this physical mirror aperture.
     *
     * <p>Only overscan is added. The returned frustum has {@code left == -right} and
     * {@code bottom == -top}; its width/height ratio exactly matches the framebuffer. This avoids
     * non-zero projection-center terms in shader-pack projection matrices while preserving the
     * original off-axis view after composition.</p>
     */
    public ViewportProjection fitViewport(int viewportWidth, int viewportHeight) {
        float halfHeight = requiredHalfHeight(viewportWidth, viewportHeight, 0.0f);
        return fitViewport(viewportWidth, viewportHeight, halfHeight);
    }

    /**
     * Returns the minimum centered half-height needed to contain this aperture while retaining a
     * normalized framebuffer guard band on every side.
     */
    float requiredHalfHeight(int viewportWidth, int viewportHeight, float guardBand) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("viewport dimensions must be positive");
        }
        if (!Float.isFinite(guardBand) || guardBand < 0.0f || guardBand >= 0.5f) {
            throw new IllegalArgumentException("guardBand must be finite and in [0, 0.5)");
        }

        double viewportAspect = (double) viewportWidth / (double) viewportHeight;
        double requiredHalfWidth = Math.max(Math.abs(left), Math.abs(right));
        double requiredHalfHeight = Math.max(Math.abs(bottom), Math.abs(top));
        double halfHeight = Math.max(requiredHalfHeight, requiredHalfWidth / viewportAspect);
        double usableHalfRange = 1.0 - 2.0 * guardBand;
        return (float) (halfHeight / usableHalfRange);
    }

    /** Builds a centered projection from a caller-owned stable envelope. */
    ViewportProjection fitViewport(int viewportWidth, int viewportHeight, float halfHeight) {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("viewport dimensions must be positive");
        }
        if (!Float.isFinite(halfHeight) || halfHeight <= 0.0f) {
            throw new IllegalArgumentException("halfHeight must be positive and finite");
        }

        float viewportAspect = (float) viewportWidth / (float) viewportHeight;
        float halfWidth = halfHeight * viewportAspect;
        float requiredHalfWidth = Math.max(Math.abs(left), Math.abs(right));
        float requiredHalfHeight = Math.max(Math.abs(bottom), Math.abs(top));
        if (halfWidth + 1.0e-5f < requiredHalfWidth || halfHeight + 1.0e-5f < requiredHalfHeight) {
            throw new IllegalArgumentException("capture envelope does not contain mirror aperture");
        }

        float captureLeft = -halfWidth;
        float captureRight = halfWidth;
        float captureBottom = -halfHeight;
        float captureTop = halfHeight;
        float captureWidth = captureRight - captureLeft;
        float captureHeight = captureTop - captureBottom;

        UvRect crop = new UvRect(
                (left - captureLeft) / captureWidth,
                (bottom - captureBottom) / captureHeight,
                (right - captureLeft) / captureWidth,
                (top - captureBottom) / captureHeight);
        return new ViewportProjection(captureLeft, captureRight, captureBottom, captureTop,
                nearPlane, crop);
    }

    public record ViewportProjection(float left, float right, float bottom, float top,
                                     float nearPlane, UvRect crop) {
        public ViewportProjection {
            if (Math.abs(left + right) > 1.0e-4f || Math.abs(bottom + top) > 1.0e-4f) {
                throw new IllegalArgumentException("capture projection must be centered");
            }
        }

        public Matrix4f matrix(float farPlane) {
            if (!Float.isFinite(farPlane) || farPlane <= nearPlane) {
                throw new IllegalArgumentException("farPlane must be finite and greater than nearPlane");
            }
            return new Matrix4f().frustum(left, right, bottom, top, nearPlane, farPlane);
        }
    }

    public record UvRect(float minU, float minV, float maxU, float maxV) {
        public UvRect {
            if (!Float.isFinite(minU) || !Float.isFinite(minV)
                    || !Float.isFinite(maxU) || !Float.isFinite(maxV)) {
                throw new IllegalArgumentException("crop values must be finite");
            }
            if (minU < -1.0e-4f || minV < -1.0e-4f
                    || maxU > 1.0001f || maxV > 1.0001f
                    || maxU <= minU || maxV <= minV) {
                throw new IllegalArgumentException("invalid reflection crop");
            }
        }

        public float centerU() {
            return (minU + maxU) * 0.5f;
        }

        public float centerV() {
            return (minV + maxV) * 0.5f;
        }

        public static UvRect full() {
            return new UvRect(0.0f, 0.0f, 1.0f, 1.0f);
        }
    }
}
