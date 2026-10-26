package com.mirror.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Position-only environment colours computed by the player's own view, reused by every reflection
 * pass of the same outer frame. A reflection shows the sky and atmosphere around the player; the
 * virtual eye only shifts the biome blend, while each colour costs a 6x6x6 biome sampling (plus
 * every mod hook on it) per pass. View-direction terms (sunrise tint) and fluid fog stay per pass.
 */
public final class MirrorOuterColors {
    private static Level skyLevel;
    private static float skyPartialTick;
    private static Vec3 skyColor;
    private static Level fogLevel;
    private static Vec3 fogBiomeColor;

    private MirrorOuterColors() {
    }

    static void beginOuterFrame() {
        skyLevel = null;
        skyColor = null;
        fogLevel = null;
        fogBiomeColor = null;
    }

    /** Returns null when no outer-frame colour applies. */
    public static Vec3 sharedSkyColor(Level level, float partialTick) {
        return MirrorLevelRenderer.isRenderingReflection() && level == skyLevel && partialTick == skyPartialTick
                ? skyColor : null;
    }

    public static void recordSkyColor(Level level, Vec3 position, float partialTick, Vec3 color) {
        if (!recordsMainView(position)) return;
        skyLevel = level;
        skyPartialTick = partialTick;
        skyColor = color;
    }

    /** Biome fog colour sampled by FogRenderer#setupColor; returns null when none applies. */
    public static Vec3 sharedFogBiomeColor() {
        return MirrorLevelRenderer.isRenderingReflection() && Minecraft.getInstance().level == fogLevel
                ? fogBiomeColor : null;
    }

    public static void recordFogBiomeColor(Vec3 samplePosition, Vec3 color) {
        // setupColor samples at (camera - 2) / 4 in quart coordinates.
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (!recordsMainView(camera) || !samplePosition.equals(camera.subtract(2.0, 2.0, 2.0).scale(0.25))) return;
        fogLevel = Minecraft.getInstance().level;
        fogBiomeColor = color;
    }

    private static boolean recordsMainView(Vec3 position) {
        return !MirrorLevelRenderer.isRenderingReflection() && RenderSystem.isOnRenderThread()
                && position.equals(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }
}
