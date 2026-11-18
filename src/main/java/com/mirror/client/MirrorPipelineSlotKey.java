package com.mirror.client;

import net.irisshaders.iris.shaderpack.programs.ProgramSet;

/** Stable identity for one secondary pipeline slot within one shader generation/program set. */
public record MirrorPipelineSlotKey(ProgramSet programSet, MirrorPassContext.PipelineSlot slot) {
    public MirrorPassContext.ResolutionBucket resolution() {
        return slot.resolution();
    }

    public String label() {
        return "programSet@" + Integer.toHexString(System.identityHashCode(programSet));
    }
}
