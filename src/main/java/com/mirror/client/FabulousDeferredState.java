package com.mirror.client;

import com.mirror.bridge.LevelRendererBridge;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;

/** Temporarily routes Fabulous render types into the active mirror target. */
final class FabulousDeferredState {
    private final LevelRendererBridge renderer;
    private final PostChain transparencyChain;
    private final RenderTarget translucentTarget;
    private final RenderTarget itemEntityTarget;
    private final RenderTarget particlesTarget;
    private final RenderTarget weatherTarget;
    private final RenderTarget cloudsTarget;
    private final RenderTarget entityTarget;

    private FabulousDeferredState(LevelRendererBridge renderer) {
        this.renderer = renderer;
        transparencyChain = renderer.mirror$getTransparencyChain();
        translucentTarget = renderer.mirror$getTranslucentTarget();
        itemEntityTarget = renderer.mirror$getItemEntityTarget();
        particlesTarget = renderer.mirror$getParticlesTarget();
        weatherTarget = renderer.mirror$getWeatherTarget();
        cloudsTarget = renderer.mirror$getCloudsTarget();
        entityTarget = renderer.mirror$getEntityTarget();
    }

    static FabulousDeferredState captureAndDisable(LevelRenderer levelRenderer) {
        LevelRendererBridge renderer = (LevelRendererBridge) levelRenderer;
        FabulousDeferredState state = new FabulousDeferredState(renderer);
        renderer.mirror$setTransparencyChain(null);
        renderer.mirror$setTranslucentTarget(null);
        renderer.mirror$setItemEntityTarget(null);
        renderer.mirror$setParticlesTarget(null);
        renderer.mirror$setWeatherTarget(null);
        renderer.mirror$setCloudsTarget(null);
        renderer.mirror$setEntityTarget(null);
        return state;
    }

    void restore() {
        renderer.mirror$setTransparencyChain(transparencyChain);
        renderer.mirror$setTranslucentTarget(translucentTarget);
        renderer.mirror$setItemEntityTarget(itemEntityTarget);
        renderer.mirror$setParticlesTarget(particlesTarget);
        renderer.mirror$setWeatherTarget(weatherTarget);
        renderer.mirror$setCloudsTarget(cloudsTarget);
        renderer.mirror$setEntityTarget(entityTarget);
    }
}
