package com.mirror.client;

import net.minecraftforge.fml.ModList;

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
}
