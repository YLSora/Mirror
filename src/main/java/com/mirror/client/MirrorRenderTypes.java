package com.mirror.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/** Render types used only to present completed mirror color textures. */
public final class MirrorRenderTypes extends RenderType {
    private static final Function<ResourceLocation, RenderType> MIRROR_SURFACE = Util.memoize(texture ->
            create("mirror_surface",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    true,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_TEX_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setWriteMaskState(COLOR_DEPTH_WRITE)
                            // Keep the same world/Fabulous output semantics as entity surfaces.
                            .setOutputState(ITEM_ENTITY_TARGET)
                            .createCompositeState(false)));

    private MirrorRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                              int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                              Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    public static RenderType mirrorSurface(ResourceLocation texture) {
        return MIRROR_SURFACE.apply(texture);
    }
}
