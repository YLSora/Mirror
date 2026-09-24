package com.mirror.compat;

import com.mirror.client.FlashlightCompat;
import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPassContext;
import com.mirror.client.OculusCompat;
import com.mojang.logging.LogUtils;

import java.util.Arrays;

/** Outer-frame clock and opt-in counters, independent of optional mod class loading. */
public final class ThirdPartyFrame {
    public enum Work {
        GRASS_DRAW, GRASS_MAINTENANCE, GRASS_SUBMIT, GRASS_UPLOAD_DRAIN,
        GRASS_LIGHT, GRASS_TRAIL, GRASS_TRAIL_UPLOAD, GRASS_WIND, GRASS_STATIC_BAKE,
        VOXY_MODEL, VOXY_NODES, VOXY_CLEAN, VOXY_LOAD, VOXY_UPLOAD, VOXY_DOWNLOAD,
        VOXY_VIEW_CREATE, VOXY_VIEW_FREE, VOXY_DEPTH_ALLOC, VOXY_HIZ_ALLOC, VOXY_COLOR_ALLOC,
        VOXY_HISTORY_RESET
    }

    private static final long[] COUNTS = new long[Work.values().length];
    private static long frame;
    private static long voxyGeometryEpoch;

    private ThirdPartyFrame() { }

    public static void begin() {
        frame++;
        Arrays.fill(COUNTS, 0L);
        GrassFrame.begin();
    }

    public static long id() { return frame; }

    public static long voxyGeometryEpoch() { return voxyGeometryEpoch; }
    public static void voxyGeometryChanged() { voxyGeometryEpoch++; }

    public static boolean auxiliary() {
        return OculusCompat.isShadowPass() || FlashlightCompat.isAuxiliaryPass();
    }

    public static boolean primary() {
        return !MirrorPassContext.isActive() && !auxiliary();
    }

    public static void count(Work work) {
        COUNTS[work.ordinal()]++;
    }

    public static void report() {
        if (!MirrorDiagnostics.isDebugEnabled()) return;
        StringBuilder counts = new StringBuilder();
        for (Work work : Work.values()) {
            long count = COUNTS[work.ordinal()];
            if (count != 0L) counts.append(work).append('=').append(count).append(' ');
        }
        if (!counts.isEmpty()) LogUtils.getLogger().info("[Mirror stageB] frame={}, {}", frame, counts);
    }
}
