package com.mirror.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorTextureKeyTest {
    @Test
    void parentChainIsPartOfCacheIdentity() {
        UUID mirror = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        MirrorTextureKey direct = new MirrorTextureKey(mirror, List.of(), 0, 64, 64);
        MirrorTextureKey nested = new MirrorTextureKey(mirror, List.of(parent), 1, 32, 32);

        assertNotEquals(direct, nested);
        assertTrue(nested.containsParent(parent));
        assertEquals(List.of(parent), nested.parentChain());
    }
}
