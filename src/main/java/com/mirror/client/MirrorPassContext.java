package com.mirror.client;

import com.mojang.blaze3d.pipeline.RenderTarget;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Render-thread context for one mirror pass.
 *
 * <p>The context is deliberately independent from a mirror UUID.  A pipeline is a resource for
 * a dimension, recursion level and resolution slot; using the mirror identity here would create
 * one shader pipeline per block and would make recursive rendering impossible to warm up
 * deterministically.</p>
 */
public final class MirrorPassContext implements AutoCloseable {
    private static final ThreadLocal<Deque<MirrorPassContext>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private final int recursionDepth;
    private final ResolutionBucket resolutionBucket;
    private final PipelineSlot pipelineSlot;
    private final RenderTarget captureTarget;
    private boolean closed;

    private MirrorPassContext(int recursionDepth, RenderTarget captureTarget) {
        if (recursionDepth < 0) throw new IllegalArgumentException("recursionDepth must be non-negative");
        if (captureTarget.width <= 0 || captureTarget.height <= 0) {
            throw new IllegalArgumentException("mirror capture target must have a positive size");
        }
        this.recursionDepth = recursionDepth;
        this.resolutionBucket = new ResolutionBucket(captureTarget.width, captureTarget.height);
        this.pipelineSlot = new PipelineSlot(recursionDepth, resolutionBucket);
        this.captureTarget = captureTarget;
    }

    public static MirrorPassContext begin(int recursionDepth, RenderTarget captureTarget) {
        MirrorPassContext context = new MirrorPassContext(recursionDepth, captureTarget);
        STACK.get().push(context);
        return context;
    }

    public static boolean isActive() {
        return !STACK.get().isEmpty();
    }

    public static MirrorPassContext current() {
        MirrorPassContext context = STACK.get().peek();
        if (context == null) throw new IllegalStateException("No active mirror pass");
        return context;
    }

    public static PipelineSlot currentPipelineSlot() {
        return current().pipelineSlot;
    }

    public int recursionDepth() {
        return recursionDepth;
    }

    public ResolutionBucket resolutionBucket() {
        return resolutionBucket;
    }

    public PipelineSlot pipelineSlot() {
        return pipelineSlot;
    }

    public RenderTarget captureTarget() {
        return captureTarget;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Deque<MirrorPassContext> contexts = STACK.get();
        if (contexts.peek() != this) {
            throw new IllegalStateException("Mirror pass contexts must close in stack order");
        }
        contexts.pop();
        if (contexts.isEmpty()) STACK.remove();
    }

    public record ResolutionBucket(int widthBucket, int heightBucket) {
    }

    public record PipelineSlot(int recursionDepth, ResolutionBucket resolution) {
    }
}
