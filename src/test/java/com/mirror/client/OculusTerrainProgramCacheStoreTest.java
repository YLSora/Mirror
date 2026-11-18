package com.mirror.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OculusTerrainProgramCacheStoreTest {
    @Test
    void retainsEntryAcrossOwnerInstanceChanges() {
        List<String> deleted = new ArrayList<>();
        OculusTerrainProgramCacheStore<String> store = new OculusTerrainProgramCacheStore<>(deleted::add);
        Object pipeline = new Object();
        Object vertexType = new Object();

        store.put(pipeline, vertexType, "programs");

        OculusTerrainProgramCacheStore.Lookup<String> lookup = store.take(pipeline, vertexType);
        assertEquals("programs", lookup.value());
        assertTrue(lookup.hadEntry());
        assertFalse(lookup.vertexTypeMismatch());
        assertTrue(deleted.isEmpty());
    }

    @Test
    void vertexTypeMismatchDropsEntry() {
        List<String> deleted = new ArrayList<>();
        OculusTerrainProgramCacheStore<String> store = new OculusTerrainProgramCacheStore<>(deleted::add);
        Object pipeline = new Object();
        Object oldVertexType = new Object();
        Object newVertexType = new Object();

        store.put(pipeline, oldVertexType, "old");

        OculusTerrainProgramCacheStore.Lookup<String> lookup = store.take(pipeline, newVertexType);
        assertNull(lookup.value());
        assertTrue(lookup.hadEntry());
        assertTrue(lookup.vertexTypeMismatch());
        assertEquals(List.of("old"), deleted);
        assertEquals(0, store.size());
    }

    @Test
    void clearDeletesAllEntries() {
        List<String> deleted = new ArrayList<>();
        OculusTerrainProgramCacheStore<String> store = new OculusTerrainProgramCacheStore<>(deleted::add);
        store.put(new Object(), new Object(), "one");
        store.put(new Object(), new Object(), "two");

        store.clear();

        assertEquals(0, store.size());
        assertEquals(2, deleted.size());
    }
}
