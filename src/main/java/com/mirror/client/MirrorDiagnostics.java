package com.mirror.client;

import com.mirror.config.MirrorConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Lightweight render-thread diagnostics for mirror pipeline and reflection costs. */
public final class MirrorDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SUMMARY_INTERVAL_FRAMES = 120L;

    private static long frameCount;
    private static long reflectionPassCount;
    private static long reflectionPassNanos;
    private static long maximumReflectionPassNanos;
    private static long deferredPipelineBuilds;
    private static long shaderSourceCompatibilityPatches;
    private static long lateShaderQuarantines;
    private static long temporalAttachmentResets;
    private static int maximumPendingViews;
    private static int maximumReflectionDepth;
    private static int maximumShouldRenderChildDepth = -1;
    private static int maximumRendererChildDepth = -1;
    private static int maximumRecursiveRequestDepth = -1;
    private static int maximumFacingRejectedChildDepth = -1;
    private static long deferredViewCount;

    private MirrorDiagnostics() {
    }

    public static void beginOuterFrame(int pendingViews) {
        frameCount++;
        maximumPendingViews = Math.max(maximumPendingViews, pendingViews);
        if (frameCount % SUMMARY_INTERVAL_FRAMES != 0L) return;
        if (reflectionPassCount == 0L && deferredPipelineBuilds == 0L
                && shaderSourceCompatibilityPatches == 0L && lateShaderQuarantines == 0L
                && temporalAttachmentResets == 0L) {
            maximumPendingViews = 0;
            return;
        }

        if (isDebugEnabled()) {
            double averageReflectionMs = reflectionPassCount == 0L
                    ? 0.0D : nanosToMillis(reflectionPassNanos) / reflectionPassCount;
            LOGGER.info("[Mirror diagnostics] 120-frame window: reflectionPasses={}, maxDepth={}, "
                            + "maxShouldRenderChildDepth={}, maxRendererChildDepth={}, maxRecursiveRequestDepth={}, "
                            + "maxFacingRejectedChildDepth={}, avg={} ms, max={} ms, "
                            + "deferredPipelineBuilds={}, shaderCompatibilityPatches={}, lateShaderQuarantines={}, "
                            + "temporalAttachmentResets={}, deferredViews={}, maxPendingViews={}",
                    reflectionPassCount, maximumReflectionDepth, maximumShouldRenderChildDepth,
                    maximumRendererChildDepth, maximumRecursiveRequestDepth,
                    maximumFacingRejectedChildDepth, formatMillis(averageReflectionMs),
                    formatMillis(nanosToMillis(maximumReflectionPassNanos)), deferredPipelineBuilds,
                    shaderSourceCompatibilityPatches, lateShaderQuarantines, temporalAttachmentResets,
                    deferredViewCount, maximumPendingViews);
        }

        reflectionPassCount = 0L;
        reflectionPassNanos = 0L;
        maximumReflectionPassNanos = 0L;
        deferredPipelineBuilds = 0L;
        shaderSourceCompatibilityPatches = 0L;
        lateShaderQuarantines = 0L;
        temporalAttachmentResets = 0L;
        deferredViewCount = 0L;
        maximumPendingViews = 0;
        maximumReflectionDepth = 0;
        maximumShouldRenderChildDepth = -1;
        maximumRendererChildDepth = -1;
        maximumRecursiveRequestDepth = -1;
        maximumFacingRejectedChildDepth = -1;
    }

    public static void recordReflectionPass(int recursionDepth, long elapsedNanos) {
        reflectionPassCount++;
        maximumReflectionDepth = Math.max(maximumReflectionDepth, recursionDepth);
        reflectionPassNanos += elapsedNanos;
        maximumReflectionPassNanos = Math.max(maximumReflectionPassNanos, elapsedNanos);
    }

    public static void recordShouldRender(int childDepth) {
        maximumShouldRenderChildDepth = Math.max(maximumShouldRenderChildDepth, childDepth);
    }

    public static void recordRendererEntry(int childDepth) {
        maximumRendererChildDepth = Math.max(maximumRendererChildDepth, childDepth);
    }

    public static void recordRecursiveRequest(int depth) {
        maximumRecursiveRequestDepth = Math.max(maximumRecursiveRequestDepth, depth);
    }

    public static void recordFacingRejected(int childDepth) {
        maximumFacingRejectedChildDepth = Math.max(maximumFacingRejectedChildDepth, childDepth);
    }

    public static void recordDeferredPipelineBuild() {
        deferredPipelineBuilds++;
    }

    public static void recordShaderSourceCompatibilityPatch() {
        shaderSourceCompatibilityPatches++;
    }

    public static void recordLateShaderQuarantine() {
        lateShaderQuarantines++;
    }

    public static void recordTemporalAttachmentReset() {
        temporalAttachmentResets++;
    }

    /** Records views deferred to the next frame by the per-frame reflection budget. */
    public static void recordDeferredViews(long count) {
        deferredViewCount += count;
    }

    public static void pipelinePrewarmed(Object dimension, long elapsedNanos, int index, int total) {
        if (!isDebugEnabled()) return;
        LOGGER.info("[Mirror diagnostics] Prewarmed mirror pipeline {}/{} for dimension {} in {} ms",
                index, total, dimension, formatMillis(nanosToMillis(elapsedNanos)));
    }

    public static void terrainProgramsPrewarmed(Object dimension, long compileNanos, long totalWarmupNanos) {
        if (!isDebugEnabled()) return;
        LOGGER.info("[Mirror diagnostics] Prewarmed mirror terrain programs for dimension {} in {} ms; "
                        + "pipeline total warm-up {} ms",
                dimension, formatMillis(nanosToMillis(compileNanos)),
                formatMillis(nanosToMillis(totalWarmupNanos)));
    }

    public static void pipelineConstructed(Object dimension, MirrorPassContext.PipelineSlot slot,
                                           long elapsedNanos) {
        if (!isDebugEnabled()) return;
        LOGGER.info("[Mirror diagnostics] Constructed mirror pipeline for dimension {}, slot {} in {} ms; "
                        + "terrain programs are still warming",
                dimension, slot, formatMillis(nanosToMillis(elapsedNanos)));
    }

    public static void terrainProgramsReady(Object dimension, MirrorPassContext.PipelineSlot slot,
                                            long compileNanos, long totalWarmupNanos) {
        if (!isDebugEnabled()) return;
        LOGGER.info("[Mirror diagnostics] Mirror pipeline READY for dimension {}, slot {}: terrain programs {} ms, "
                        + "total slot warm-up {} ms",
                dimension, slot, formatMillis(nanosToMillis(compileNanos)),
                formatMillis(nanosToMillis(totalWarmupNanos)));
    }

    private static boolean isDebugEnabled() {
        return MirrorConfig.CLIENT.debug.get();
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static String formatMillis(double millis) {
        return String.format(java.util.Locale.ROOT, "%.2f", millis);
    }
}
