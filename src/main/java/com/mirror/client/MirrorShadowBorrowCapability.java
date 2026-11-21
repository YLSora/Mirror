package com.mirror.client;

import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;

import java.util.List;
import java.util.Set;

/** Implemented only when the required mirror-shadow safety injections are present. */
public interface MirrorShadowBorrowCapability {
    ShadowRenderTargets mirror$getShadowRenderTargets();

    ShadowRenderer mirror$getShadowRenderer();

    int mirror$getShadowMapResolution();

    CustomTextureManager mirror$getCustomTextureManager();

    ShaderPack mirror$getShaderPack();

    int mirror$getNoiseTextureResolution();

    Set<GlImage> mirror$getCustomImages();

    boolean mirror$usesBorrowedShadowTargets();

    boolean mirror$usesBorrowedCustomTextures();

    boolean mirror$usesBorrowedCustomImages();

    default boolean mirror$dependsOnPrimary() {
        return mirror$usesBorrowedShadowTargets() || mirror$usesBorrowedCustomTextures()
                || mirror$usesBorrowedCustomImages();
    }

    WorldRenderingPipeline mirror$getPrimaryOwner();

    void mirror$setPrimaryOwner(WorldRenderingPipeline owner);

    void mirror$adoptShadowFramebuffers(List<GlFramebuffer> framebuffers);
}
