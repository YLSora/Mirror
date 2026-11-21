package com.mirror.client;

import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;

import java.util.List;

public interface MirrorShadowRenderTargetsAccess {
    boolean[] mirror$getFlipped();

    List<GlFramebuffer> mirror$getOwnedFramebuffers();
}
