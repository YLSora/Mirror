package com.mirror.client;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

import java.util.List;

/** Exact pipeline state access used by the outer-state guard. */
public interface MirrorPipelineAccess {
    WorldRenderingPipeline mirror$getPipeline();
    void mirror$setPipeline(WorldRenderingPipeline pipeline);

    /** Starts a real outer frame and replenishes the single heavyweight slot-build budget. */
    void mirror$beginFrame();

    /** Claims this frame's heavyweight budget immediately before terrain programs are created. */
    void mirror$beginTerrainProgramBuild(WorldRenderingPipeline pipeline);

    /** Marks a mirror pipeline READY only after Oculus/Embeddium terrain programs were created. */
    void mirror$markTerrainProgramsReady(WorldRenderingPipeline pipeline, long compileNanos);

    /** Pipelines constructed during normal Oculus loading and awaiting terrain-program warm-up. */
    List<WorldRenderingPipeline> mirror$getPrewarmedPipelinesNeedingTerrain(WorldRenderingPipeline primaryPipeline);

    /** Quarantines this shader generation after a secondary terrain shader compile/link failure. */
    void mirror$quarantineShaderGeneration(WorldRenderingPipeline pipeline, Throwable failure);

    /** Releases view-scoped state; heavyweight shader pipelines remain owned by their stable slot. */
    void mirror$releaseMirrorView(long viewId);
}
