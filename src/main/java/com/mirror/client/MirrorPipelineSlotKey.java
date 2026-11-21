package com.mirror.client;

import net.irisshaders.iris.shaderpack.programs.ProgramSet;

/** Stable identity for one secondary pipeline per semantic shader set and recursion layer. */
public record MirrorPipelineSlotKey(ProgramSet programSet, int recursionDepth) {
    public String label() {
        return "programSet@" + Integer.toHexString(System.identityHashCode(programSet))
                + "/depth=" + recursionDepth;
    }
}
