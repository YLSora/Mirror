package com.mirror.client;

import net.irisshaders.iris.pipeline.WorldRenderingPipeline;

/** Exact pipeline state access used by the outer-state guard. */
public interface MirrorPipelineAccess {
    WorldRenderingPipeline mirror$getPipeline();

    void mirror$setPipeline(WorldRenderingPipeline pipeline);

    void mirror$clearMirrorPipelines();
}
