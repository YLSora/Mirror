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
    private final WorldRenderingPipeline owner;
    private final long constructionStartedNanos;
    private boolean terrainReady;
    private boolean failed;

    public MirrorPipelineState(WorldRenderingPipeline pipeline, WorldRenderingPipeline owner,
                               long constructionStartedNanos) {
        this.pipeline = pipeline;
        this.owner = owner;
        this.constructionStartedNanos = constructionStartedNanos;
    }

    public WorldRenderingPipeline pipeline() {
        return pipeline;
    }

    public WorldRenderingPipeline owner() {
        return owner;
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

    public void markTerrainNotReady() {
        terrainReady = false;
    }

    public boolean failed() {
        return failed;
    }

    public void markFailed() {
        terrainReady = false;
        failed = true;
    }
}
