package com.mirror.client;

import java.lang.reflect.Field;

/** Optional pass-boundary bridge for Flashier Flashlights. */
public final class FlashlightCompat {
    private static final Field ALBEDO_ACTIVE = findFlag("FlashlightAlbedoFlag");
    private static final Field SHADOW_ACTIVE = findFlag("FlashlightShadowFlag");

    private FlashlightCompat() {
    }

    /**
     * Flashier re-renders block entities into material and shadow targets. A completed mirror
     * surface is scene color, not source geometry for either target, so it must not be submitted
     * during those auxiliary passes.
     */
    static boolean isAuxiliaryPass() {
        return isSet(ALBEDO_ACTIVE) || isSet(SHADOW_ACTIVE);
    }

    static boolean shouldDeferSurfacePresentation() {
        return ALBEDO_ACTIVE != null && SHADOW_ACTIVE != null
                && !OculusCompat.isShaderPackInUse();
    }

    /** Called by Flashier immediately after its primary screen-space lighting pass. */
    public static void presentDeferredSurfaces() {
        if (!OculusCompat.isShaderPackInUse()) {
            DeferredMirrorSurfaceRenderer.flushCurrentPass();
        }
    }

    private static Field findFlag(String className) {
        try {
            return Class.forName("com.keerdm.flashlightmod.compat." + className).getField("ACTIVE");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean isSet(Field field) {
        if (field == null) return false;
        try {
            return field.getBoolean(null);
        } catch (IllegalAccessException ignored) {
            return false;
        }
    }
}
