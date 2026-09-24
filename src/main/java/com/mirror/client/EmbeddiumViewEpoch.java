package com.mirror.client;

/**
 * Generation of the shared Embeddium section graph. Mirror visibility lists may be reused only
 * while this generation is unchanged; a block/chunk graph mutation invalidates every view.
 */
public final class EmbeddiumViewEpoch {
    private static long generation;

    private EmbeddiumViewEpoch() {
    }

    public static long current() {
        return generation;
    }

    public static void invalidate() {
        generation++;
    }
}
