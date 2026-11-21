package com.mirror.client;

import java.util.HashMap;
import java.util.Map;

/** GL-free sizing, capacity, failure and generation policy for shader mirror pipelines. */
public final class MirrorShaderPolicy {
    private final Map<Integer, LayerSize> layerSizes = new HashMap<>();
    private final Map<Integer, Failure> failures = new HashMap<>();
    private Object programSet;
    private boolean frameOpen;
    private int buildDepth = -1;
    private boolean constructed;
    private String shadowBorrowFailureClass;
    private String customTextureBorrowFailureClass;
    private String customImageBorrowFailureClass;

    public void beginFrame() {
        buildDepth = -1;
        constructed = false;
        for (LayerSize state : layerSizes.values()) state.frameDemand = 0;
        frameOpen = true;
    }

    public boolean requestSize(int depth, int bucket) {
        if (depth < 0 || bucket <= 0) return false;
        // Multiple camera passes can seal sizes in one render tick, but share its build budget.
        frameOpen = true;
        LayerSize state = layerSizes.computeIfAbsent(depth, ignored -> new LayerSize());
        state.frameDemand = Math.max(state.frameDemand, bucket);
        return true;
    }

    public void finishFrame(long nowNanos, long shrinkDelayNanos) {
        if (!frameOpen) return;
        for (LayerSize state : layerSizes.values()) {
            int demand = state.frameDemand;
            if (demand == 0) {
                state.shrinkSince = Long.MIN_VALUE;
                continue;
            }
            if (state.active == 0 || demand > state.active) {
                state.active = demand;
                state.shrinkSince = Long.MIN_VALUE;
            } else if (demand < state.active) {
                if (state.shrinkSince == Long.MIN_VALUE) state.shrinkSince = nowNanos;
                if (nowNanos - state.shrinkSince >= shrinkDelayNanos) {
                    state.active = demand;
                    state.shrinkSince = Long.MIN_VALUE;
                }
            } else {
                state.shrinkSince = Long.MIN_VALUE;
            }
            state.frameDemand = 0;
        }
        frameOpen = false;
    }

    public int activeSize(int depth) {
        LayerSize state = layerSizes.get(depth);
        return state == null ? 0 : state.active;
    }

    /** Construction and terrain linking for the same layer share one budget. */
    public boolean claimBuild(int depth) {
        if (buildDepth != -1 && buildDepth != depth) return false;
        buildDepth = depth;
        return true;
    }

    public boolean claimConstruction(int depth) {
        if (constructed || !claimBuild(depth)) return false;
        constructed = true;
        return true;
    }

    public static int maxActiveDepth(int maxRecursionDepth, int maxPipelines) {
        return Math.max(1, Math.min(maxRecursionDepth, maxPipelines));
    }

    public static boolean reuseParent(int requestedDepth, int maxRecursionDepth, int maxPipelines) {
        return requestedDepth >= maxActiveDepth(maxRecursionDepth, maxPipelines);
    }

    public boolean mayAttempt(int depth, long nowNanos) {
        Failure state = failures.get(depth);
        return state == null || (state.count < 3 && nowNanos - state.retryAtNanos >= 0);
    }

    public void failed(int depth, long nowNanos, long cooldownNanos) {
        Failure state = failures.computeIfAbsent(depth, ignored -> new Failure());
        state.count++;
        state.retryAtNanos = nowNanos + cooldownNanos;
    }

    public int failureCount(int depth) {
        Failure state = failures.get(depth);
        return state == null ? 0 : state.count;
    }

    public boolean mayBorrowShadowTargets() {
        return shadowBorrowFailureClass == null;
    }

    public String shadowBorrowFailureClass() {
        return shadowBorrowFailureClass;
    }

    public void shadowBorrowFailed(String exceptionClass) {
        shadowBorrowFailureClass = exceptionClass;
    }

    public boolean mayBorrowCustomTextures() {
        return customTextureBorrowFailureClass == null;
    }

    public String customTextureBorrowFailureClass() {
        return customTextureBorrowFailureClass;
    }

    public void customTextureBorrowFailed(String exceptionClass) {
        customTextureBorrowFailureClass = exceptionClass;
    }

    public boolean mayBorrowCustomImages() {
        return customImageBorrowFailureClass == null;
    }

    public String customImageBorrowFailureClass() {
        return customImageBorrowFailureClass;
    }

    public void customImageBorrowFailed(String exceptionClass) {
        customImageBorrowFailureClass = exceptionClass;
    }

    /** @return true when a different semantic ProgramSet replaced the active one. */
    public boolean activateProgramSet(Object next) {
        if (programSet == next) return false;
        programSet = next;
        layerSizes.clear();
        return true;
    }

    public void resetGeneration() {
        programSet = null;
        shadowBorrowFailureClass = null;
        customTextureBorrowFailureClass = null;
        customImageBorrowFailureClass = null;
        failures.clear();
        layerSizes.clear();
    }

    public void logout() {
        programSet = null;
        layerSizes.clear();
    }

    private static final class LayerSize {
        private int active;
        private int frameDemand;
        private long shrinkSince = Long.MIN_VALUE;
    }

    private static final class Failure {
        private int count;
        private long retryAtNanos;
    }
}
