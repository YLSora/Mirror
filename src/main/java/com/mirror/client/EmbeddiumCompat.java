package com.mirror.client;

import net.minecraftforge.fml.ModList;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;

/**
 * Keeps the vanilla section-storage transaction out of Embeddium's renderer.  Embeddium owns its
 * compiled-section graph and its culling queue; replacing LevelRenderer.renderChunkStorage while
 * it is rendering leaves the two graphs out of sync.
 */
public final class EmbeddiumCompat {
    private static final boolean LOADED = ModList.get().isLoaded("embeddium");

    private EmbeddiumCompat() {
    }

    public static boolean ownsSectionCulling() {
        return LOADED;
    }

    public static void verifySupportedVersion() {
        if (!LOADED) return;
        String version = net.minecraftforge.fml.ModList.get().getModContainerById("embeddium")
                .orElseThrow(() -> new IllegalStateException(
                        "Embeddium was reported as loaded but has no mod metadata"))
                .getModInfo().getVersion().toString();
        if (!"0.3.31+mc1.20.1".equals(version)) {
            throw new IllegalStateException("Mirror supports Embeddium 0.3.31+mc1.20.1 only; found " + version);
        }
    }

    public static void releaseMirrorView(long viewId) {
        if (!LOADED) return;
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer instanceof EmbeddiumViewStateAccess access) access.mirror$releaseView(viewId);
    }

    public static void finishCapture(long viewId) {
        if (!LOADED) return;
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer instanceof EmbeddiumViewStateAccess access) access.mirror$finishCapture(viewId);
    }
}
