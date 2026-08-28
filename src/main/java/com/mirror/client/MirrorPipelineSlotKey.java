package com.mirror.client;

import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;

/**
 * Stable identity for one secondary Oculus mirror pipeline within a shader generation.
 *
 * <p>Recursion depth is part of the identity even when two passes use the same framebuffer
 * resolution. A recursive mirror render is nested inside its parent render; sharing one
 * {@code IrisRenderingPipeline} instance between those two passes makes the child overwrite the
 * parent's render-target flip state, persistent colortex data and shadow attachments before the
 * parent has finalized. That manifests as partially white/incorrectly shaded terrain, especially
 * for 256x256 captures used by 1x1 mirrors and recursive views.</p>
 *
 * <p>Different mirror views at the same recursion depth and resolution may still reuse the same
 * heavyweight pipeline sequentially. Only simultaneously nested recursion levels are isolated.</p>
 */
public record MirrorPipelineSlotKey(NamespacedId dimension, MirrorPassContext.PipelineSlot slot) {
    /** Convenience accessor retained for diagnostics and prewarm logging. */
    public MirrorPassContext.ResolutionBucket resolution() {
        return slot.resolution();
    }
}
