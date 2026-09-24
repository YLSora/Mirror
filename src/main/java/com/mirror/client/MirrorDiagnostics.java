package com.mirror.client;

import com.mirror.config.MirrorConfig;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/** Lightweight render-thread diagnostics for mirror pipeline and reflection costs. */
public final class MirrorDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SUMMARY_INTERVAL_FRAMES = 120;

    public enum Stage {
        STATE_SAVE, CAMERA_PREPARE, WORLD_PREPARE, WORLD_CAPTURE,
        COMPOSE, STATE_RESTORE, PASS_TOTAL, BATCH_TOTAL, THIRD_PARTY_MAINTENANCE
    }

    public enum Rejection {
        OFFSCREEN, BACKFACE, DISTANCE, DEPTH, PIXELS, CAPACITY, NO_ROOT, REMOVED, PATH, PIPELINE
    }

    private static final Stage[] STAGES = Stage.values();
    private static final Rejection[] REJECTIONS = Rejection.values();
    private static final long[] FRAME_NANOS = new long[STAGES.length];
    private static final long[][] FRAME_SAMPLES = new long[STAGES.length][SUMMARY_INTERVAL_FRAMES];
    private static final int[] FRAME_REJECTIONS = new int[REJECTIONS.length];
    private static final MirrorGpuTimer GPU_TIMER = new MirrorGpuTimer();
    private static final long[] GPU_SAMPLES = new long[SUMMARY_INTERVAL_FRAMES];
    private static boolean debugEnabled;
    private static int sampledFrames;
    private static int gpuSamples;
    private static int directRequests;
    private static int childRequests;
    private static int directCaptures;
    private static int recursiveCaptures;
    private static int frameDeferredViews;
    private static int frameCameraEntities;
    private static long cameraEntityCount;
    private static int gpuSkipped;
    private static int shadowPasses;
    private static int nestedShadowSuppressed;

    private static long frameCount;
    private static long reflectionPassCount;
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

    public static void beginOuterFrame(long frame) {
        boolean enabled = MirrorConfig.CLIENT.debug.get();
        if (enabled != debugEnabled) {
            sampledFrames = 0;
            gpuSamples = 0;
            resetSummary();
            if (!enabled) GPU_TIMER.clear();
        }
        debugEnabled = enabled;
        frameCount = frame;
        directRequests = 0;
        childRequests = 0;
        directCaptures = 0;
        recursiveCaptures = 0;
        frameDeferredViews = 0;
        frameCameraEntities = 0;
        gpuSkipped = 0;
        shadowPasses = 0;
        nestedShadowSuppressed = 0;
        Arrays.fill(FRAME_NANOS, 0L);
        Arrays.fill(FRAME_REJECTIONS, 0);
        if (debugEnabled) GPU_TIMER.poll(frame);
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static long startTimer() {
        return debugEnabled ? System.nanoTime() : 0L;
    }

    public static void finishTimer(Stage stage, long start) {
        if (start != 0L) FRAME_NANOS[stage.ordinal()] += System.nanoTime() - start;
    }

    public static int beginGpuBatch() {
        if (!debugEnabled) return -1;
        int slot = GPU_TIMER.begin(frameCount);
        if (slot < 0) gpuSkipped++;
        return slot;
    }

    public static void endGpuBatch(int slot) {
        GPU_TIMER.end(slot);
    }

    static void recordGpuBatch(long frame, long elapsedNanos) {
        if (gpuSamples < GPU_SAMPLES.length) GPU_SAMPLES[gpuSamples++] = elapsedNanos;
        LOGGER.info("[Mirror GPU] frame={}, batchMs={}", frame, formatMillis(nanosToMillis(elapsedNanos)));
    }

    public static void recordRequest(boolean child) {
        if (child) childRequests++;
        else directRequests++;
    }

    public static void reject(Rejection reason) {
        FRAME_REJECTIONS[reason.ordinal()]++;
    }

    public static void recordPending(int pendingViews) {
        maximumPendingViews = Math.max(maximumPendingViews, pendingViews);
    }

    public static void recordView(MirrorTextureKey key, Set<UUID> roots, double projectedPixels) {
        if (!debugEnabled) return;
        LOGGER.info("[Mirror view] frame={}, roots={}, mirror={}, depth={}, projectedPixels={}",
                frameCount, roots, key.mirrorId(), key.depth(),
                formatMillis(projectedPixels));
    }

    public static void recordResource(String event, long viewId, int width, int height) {
        if (debugEnabled) LOGGER.info("[Mirror resource] frame={}, event={}, view={}, size={}x{}",
                frameCount, event, viewId, width, height);
    }

    public static void recordCapture(long viewId, UUID mirrorId, int depth, int width, int height,
                                     boolean completed, long start) {
        if (!debugEnabled) return;
        LOGGER.info("[Mirror capture] frame={}, view={}, mirror={}, depth={}, capture={}x{}, completed={}, cpuMs={}",
                frameCount, viewId, mirrorId, depth, width, height, completed,
                formatMillis(nanosToMillis(System.nanoTime() - start)));
    }

    public static void recordCameraEntity() {
        frameCameraEntities++;
        cameraEntityCount++;
    }

    public static void recordWorldCapture(int depth) {
        if (depth == 0) directCaptures++;
        else recursiveCaptures++;
        reflectionPassCount++;
        maximumReflectionDepth = Math.max(maximumReflectionDepth, depth);
    }

    public static void recordShadowPass(boolean nested) {
        if (nested) nestedShadowSuppressed++;
        else shadowPasses++;
    }

    public static void endOuterFrame(int visibleRoots, int pendingViews) {
        if (!debugEnabled) return;
        StringBuilder rejections = new StringBuilder();
        for (Rejection reason : REJECTIONS) {
            int count = FRAME_REJECTIONS[reason.ordinal()];
            if (count > 0) rejections.append(reason).append('=').append(count).append(' ');
        }
        LOGGER.info("[Mirror frame] frame={}, roots={}, requestsDirect={}, requestsChild={}, "
                        + "capturesDirect={}, capturesRecursive={}, deferred={}, pending={}, rejected=[{}], "
                        + "newCameraEntities={}, cameraEntitiesTotal={}, batchCpuMs={}, gpuSkipped={}",
                frameCount, visibleRoots, directRequests, childRequests, directCaptures, recursiveCaptures,
                frameDeferredViews, pendingViews, rejections, frameCameraEntities, cameraEntityCount,
                formatMillis(nanosToMillis(FRAME_NANOS[Stage.BATCH_TOTAL.ordinal()])), gpuSkipped);
        LOGGER.info("[Mirror shadow] frame={}, primaryPasses={}, nestedSuppressed={}",
                frameCount, shadowPasses, nestedShadowSuppressed);
        for (Stage stage : STAGES) {
            FRAME_SAMPLES[stage.ordinal()][sampledFrames] = FRAME_NANOS[stage.ordinal()];
        }
        if (++sampledFrames < SUMMARY_INTERVAL_FRAMES) return;

        StringBuilder timings = new StringBuilder();
        for (Stage stage : STAGES) {
            timings.append(stage).append('=').append(percentiles(FRAME_SAMPLES[stage.ordinal()], sampledFrames))
                    .append(' ');
        }
        LOGGER.info("[Mirror timings] {} outer frames; CPU ms/frame p50/p95/p99: {}; "
                        + "resolved GPU batch ms p50/p95/p99={} (samples={})",
                sampledFrames, timings, percentiles(GPU_SAMPLES, gpuSamples), gpuSamples);
        LOGGER.info("[Mirror diagnostics] 120-frame window: worldCaptures={}, maxDepth={}, "
                        + "maxShouldRenderChildDepth={}, maxRendererChildDepth={}, maxRecursiveRequestDepth={}, "
                        + "maxFacingRejectedChildDepth={}, deferredPipelineBuilds={}, shaderCompatibilityPatches={}, "
                        + "lateShaderQuarantines={}, temporalAttachmentResets={}, deferredViews={}, maxPendingViews={}",
                reflectionPassCount, maximumReflectionDepth, maximumShouldRenderChildDepth, maximumRendererChildDepth,
                maximumRecursiveRequestDepth, maximumFacingRejectedChildDepth, deferredPipelineBuilds,
                shaderSourceCompatibilityPatches, lateShaderQuarantines, temporalAttachmentResets,
                deferredViewCount, maximumPendingViews);
        sampledFrames = 0;
        gpuSamples = 0;
        resetSummary();
    }

    private static void resetSummary() {
        reflectionPassCount = 0L;
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

    public static void clear() {
        GPU_TIMER.clear();
        debugEnabled = false;
        sampledFrames = 0;
        gpuSamples = 0;
        cameraEntityCount = 0L;
        resetSummary();
    }

    private static String percentiles(long[] samples, int count) {
        if (count == 0) return "unavailable";
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        return formatMillis(nanosToMillis(sorted[(int) Math.ceil(count * 0.50) - 1])) + "/"
                + formatMillis(nanosToMillis(sorted[(int) Math.ceil(count * 0.95) - 1])) + "/"
                + formatMillis(nanosToMillis(sorted[(int) Math.ceil(count * 0.99) - 1]));
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
        frameDeferredViews += (int) count;
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

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0D;
    }

    private static String formatMillis(double millis) {
        return String.format(java.util.Locale.ROOT, "%.2f", millis);
    }
}
