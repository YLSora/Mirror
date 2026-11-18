package com.mirror.client;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

/** Exact pipeline state access used by the outer-state guard. */
public interface MirrorPipelineAccess {
    WorldRenderingPipeline mirror$getPipeline();
    void mirror$setPipeline(WorldRenderingPipeline pipeline);

    /** Starts a real outer frame and replenishes the single heavyweight slot-build budget. */
    void mirror$beginFrame();

    /** Builds at most one queued spare after all mirror demand in this frame has had priority. */
    void mirror$finishFrame();

    /** Claims this frame's heavyweight budget immediately before terrain programs are created. */
    void mirror$beginTerrainProgramBuild(WorldRenderingPipeline pipeline);

    /** Marks a mirror pipeline READY only after Oculus/Embeddium terrain programs were created. */
    void mirror$markTerrainProgramsReady(WorldRenderingPipeline pipeline, long compileNanos);

    /** Invalidates terrain readiness after a vertex-format-specific program cache entry is dropped. */
    void mirror$markTerrainProgramsNotReady(WorldRenderingPipeline pipeline);

    /** Quarantines this shader generation after a secondary terrain shader compile/link failure. */
    void mirror$quarantineShaderGeneration(WorldRenderingPipeline pipeline, Throwable failure);

    /** Releases view-scoped state; heavyweight shader pipelines remain owned by their stable slot. */
    void mirror$releaseMirrorView(long viewId);
}
