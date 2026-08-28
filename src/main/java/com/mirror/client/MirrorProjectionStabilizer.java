package com.mirror.client;

/**
 * Keeps the shader-safe centered capture projection away from framebuffer edges and prevents its
 * FOV envelope from changing for every sub-pixel lateral camera movement.
 */
final class MirrorProjectionStabilizer {
    /**
     * Shader packs often apply vignette, TAA, SSR, bloom and other full-screen kernels before the
     * mirror crops the useful aperture. Keep that aperture well inside the secondary framebuffer
     * so those effects do not treat part of the actual mirror image as a screen edge.
     */
    static final float SHADER_GUARD_BAND = 0.14f;
    static final float VANILLA_GUARD_BAND = 0.02f;
    private static final float EXPANSION_HEADROOM = 1.10f;
    private static final float SHRINK_THRESHOLD = 0.70f;

    private float stableHalfHeight = Float.NaN;
    private float stableGuardBand = Float.NaN;

    MirrorProjection.ViewportProjection fit(MirrorProjection projection,
                                            int viewportWidth, int viewportHeight) {
        return fit(projection, viewportWidth, viewportHeight, SHADER_GUARD_BAND);
    }

    MirrorProjection.ViewportProjection fit(MirrorProjection projection,
                                            int viewportWidth, int viewportHeight,
                                            float guardBand) {
        if (!Float.isFinite(stableGuardBand)
                || Math.abs(stableGuardBand - guardBand) > 1.0e-6f) {
            stableHalfHeight = Float.NaN;
            stableGuardBand = guardBand;
        }

        float requiredHalfHeight = projection.requiredHalfHeight(
                viewportWidth, viewportHeight, guardBand);
        if (!Float.isFinite(stableHalfHeight)
                || requiredHalfHeight > stableHalfHeight
                || requiredHalfHeight < stableHalfHeight * SHRINK_THRESHOLD) {
            stableHalfHeight = requiredHalfHeight * EXPANSION_HEADROOM;
        }
        return projection.fitViewport(viewportWidth, viewportHeight, stableHalfHeight);
    }

    /**
     * Linear capture-size multiplier that keeps the physical mirror aperture at approximately the
     * same texel density as the Vanilla guard band. Shader overscan is intentionally retained; the
     * extra capture resolution pays for the pixels consumed by TAA/SSR/bloom-safe margins instead
     * of shrinking those margins and reintroducing edge artifacts.
     */
    static float shaderSamplingCompensation() {
        return visibleFraction(VANILLA_GUARD_BAND) / visibleFraction(SHADER_GUARD_BAND);
    }

    private static float visibleFraction(float guardBand) {
        return 1.0f - 2.0f * guardBand;
    }

    void reset() {
        stableHalfHeight = Float.NaN;
        stableGuardBand = Float.NaN;
    }
}
