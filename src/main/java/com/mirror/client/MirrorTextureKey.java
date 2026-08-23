package com.mirror.client;

import java.util.List;
import java.util.UUID;

/** Stable cache identity for one mirror view, including its recursive parent chain. */
public record MirrorTextureKey(UUID mirrorId, List<UUID> parentChain, int depth, int width, int height) {
    public MirrorTextureKey {
        parentChain = List.copyOf(parentChain);
    }

    public boolean containsParent(UUID id) {
        return parentChain.contains(id);
    }
}
