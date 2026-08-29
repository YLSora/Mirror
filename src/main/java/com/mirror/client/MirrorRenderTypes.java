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

    /**
     * Post-Oculus presentation layer for a completed reflection.
     *
     * <p>The world pass has already written the physical mirror/block depth before Oculus runs its
     * final/composite stages. Re-drawing an almost coplanar quad afterwards with the ordinary
     * surface state can therefore lose large, view-dependent regions to depth precision and to
     * shader packs that preserve/transform the scene depth differently. Treat the completed mirror
     * as a decal: keep LEQUAL so genuinely nearer world geometry still occludes it, bias this quad
     * slightly toward the camera, and do not write depth a second time.</p>
     */
    private static final Function<ResourceLocation, RenderType> DEFERRED_MIRROR_SURFACE = Util.memoize(texture ->
            create("mirror_surface_deferred",
                    DefaultVertexFormat.POSITION_COLOR_TEX,
                    VertexFormat.Mode.QUADS,
                    256,
                    false,
                    true,
                    CompositeState.builder()
                            .setShaderState(POSITION_COLOR_TEX_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setCullState(NO_CULL)
                            .setLayeringState(POLYGON_OFFSET_LAYERING)
                            .setWriteMaskState(COLOR_WRITE)
                            .setOutputState(MAIN_TARGET)
                            .createCompositeState(false)));

    private MirrorRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                              int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                              Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    public static RenderType mirrorSurface(ResourceLocation texture) {
        return MIRROR_SURFACE.apply(texture);
    }

    public static RenderType deferredMirrorSurface(ResourceLocation texture) {
        return DEFERRED_MIRROR_SURFACE.apply(texture);
    }
}
