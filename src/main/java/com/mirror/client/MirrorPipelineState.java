package com.mirror.client;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

/**
 * Mutable lifecycle state for one long-lived secondary Oculus mirror pipeline.
 *
 * <p>This is deliberately a normal client class rather than a nested type inside a Mixin class.
 * Sponge Mixin 0.8.5 forbids loading auxiliary classes from a configured mixin package.</p>
 */
public final class MirrorPipelineState {
    private final WorldRenderingPipeline pipeline;
    private final long constructionStartedNanos;
    private boolean terrainReady;
    private long heavyWorkFrame;

    public MirrorPipelineState(WorldRenderingPipeline pipeline, long constructionStartedNanos,
                               long heavyWorkFrame) {
        this.pipeline = pipeline;
        this.constructionStartedNanos = constructionStartedNanos;
        this.heavyWorkFrame = heavyWorkFrame;
    }

    public WorldRenderingPipeline pipeline() {
        return pipeline;
    }

    public long constructionStartedNanos() {
        return constructionStartedNanos;
    }

    public boolean terrainReady() {
        return terrainReady;
    }

    public void markTerrainReady() {
        terrainReady = true;
    }

    public long heavyWorkFrame() {
        return heavyWorkFrame;
    }

    public void setHeavyWorkFrame(long heavyWorkFrame) {
        this.heavyWorkFrame = heavyWorkFrame;
    }
}
