package com.mirror.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;

/** TextureManager handle for the color attachment of a RenderTarget. */
public final class MirrorRenderTargetTexture extends AbstractTexture {
    private final RenderTarget target;

    public MirrorRenderTargetTexture(RenderTarget target) {
        this.target = target;
        this.id = target.getColorTextureId();
    }

    public void refreshId() {
        this.id = target.getColorTextureId();
    }

    @Override
    public void load(ResourceManager manager) throws IOException {
        // The OpenGL image is owned by the RenderTarget, not by a resource pack.
    }

    @Override
    public void close() {
        // The owning MirrorReflectionTexture destroys the RenderTarget.
    }
}
