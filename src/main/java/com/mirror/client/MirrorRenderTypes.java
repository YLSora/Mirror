package com.mirror.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public final class MirrorRenderTypes extends RenderType {
    private static final ResourceLocation UNDERLAY = new ResourceLocation("mirror", "textures/block/mirror/underlay.png");
    private static final ResourceLocation OVERLAY = new ResourceLocation("mirror", "textures/block/mirror/overlay.png");
    private static final RenderStateShard.ShaderStateShard MIRROR_SHADER =
            new RenderStateShard.ShaderStateShard(() -> MirrorClient.MIRROR_SHADER);

    private MirrorRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                              boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState,
                              Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    private MirrorRenderTypes() {
        super("mirror_internal", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                1536, true, false, () -> { }, () -> { });
    }

    public static RenderType material(ResourceLocation reflection, int width, int height) {
        return TYPES.apply(new Key(reflection, width, height));
    }

    private static final Function<Key, RenderType> TYPES = net.minecraft.Util.memoize(key -> {
        RenderStateShard.MultiTextureStateShard textures = RenderStateShard.MultiTextureStateShard.builder()
                .add(key.reflection(), MirrorConfigBridge.smooth(), false)
                .add(UNDERLAY, false, false)
                .build();
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(MIRROR_SHADER)
                .setTransparencyState(NO_TRANSPARENCY)
                // The reflection target already contains world lighting. Leaving the vanilla
                // lightmap state enabled would reserve texture unit 2 and overwrite the overlay
                // sampler used by this material.
                .setLightmapState(NO_LIGHTMAP)
                .setOverlayState(NO_OVERLAY)
                .setCullState(NO_CULL)
                .setLayeringState(POLYGON_OFFSET_LAYERING)
                .setTextureState(textures)
                .setTexturingState(new RenderStateShard.TexturingStateShard("mirror_uniforms",
                        () -> setUniforms(key), () -> {
                        }))
                .createCompositeState(false);
        return RenderType.create("mirror_material", DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1536, true, false, state);
    });

    private static void setUniforms(Key key) {
        RenderSystem.setShaderTexture(2, OVERLAY);
        ShaderInstance shader = MirrorClient.MIRROR_SHADER;
        if (shader == null) return;
        shader.safeGetUniform("Tiles").set((float) key.width(), (float) key.height());
        shader.safeGetUniform("Fade").set(MirrorTextureManager.fade(key.reflection()));
    }

    private record Key(ResourceLocation reflection, int width, int height) {
    }

    private static final class MirrorConfigBridge {
        private static boolean smooth() {
            return com.mirror.config.MirrorConfig.CLIENT.smoothSampling.get();
        }
    }
}
