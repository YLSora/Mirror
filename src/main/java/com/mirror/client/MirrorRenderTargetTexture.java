package com.mirror.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;

/** TextureManager handle for the color attachment of a RenderTarget. */
public final class MirrorRenderTargetTexture extends AbstractTexture {
    private final RenderTarget target;

    public MirrorRenderTargetTexture(RenderTarget target) {
        this.target = target;
    }

    /**
     * The GL image is owned by the RenderTarget and its id is only assigned when the target is first
     * bound. Resolve it lazily from the owning target instead of caching a value captured before the
     * first bind, which would be 0 (the default texture) for a freshly created target and would keep
     * the mirror surface sampling a stale id across a resolution-layout transition.
     */
    @Override
    public int getId() {
        RenderSystem.assertOnRenderThreadOrInit();
        return target.getColorTextureId();
    }

    public void refreshId() {
        // getId() resolves the color texture id from the owning RenderTarget on every call.
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
