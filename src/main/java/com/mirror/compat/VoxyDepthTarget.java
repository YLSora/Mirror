package com.mirror.compat;

/** Selects a depth texture while preserving the pipeline's existing color attachments. */
public interface VoxyDepthTarget {
    void mirror$selectDepth(long view);
}
