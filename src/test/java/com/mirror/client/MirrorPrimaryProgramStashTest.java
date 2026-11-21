package com.mirror.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MirrorPrimaryProgramStashTest {
    @Test
    void primaryMirrorAlternationRetainsProgramsWithoutDisposal() {
        List<String> disposed = new ArrayList<>();
        MirrorPrimaryProgramStash<String> stash = new MirrorPrimaryProgramStash<>(disposed::add);
        Object primary = new Object();
        Object mirror = new Object();
        Object format = new Object();

        stash.store(primary, format, "primary-programs"); // primary -> mirror
        assertEquals("primary-programs", stash.take(primary, format)); // mirror -> primary
        stash.store(primary, format, "primary-programs"); // primary -> mirror again
        assertEquals("primary-programs", stash.take(primary, format)); // mirror -> primary again

        assertTrue(stash.isEmpty());
        assertTrue(disposed.isEmpty());
        assertNotSame(primary, mirror);
    }

    @Test
    void versionBumpDisposesPrimaryStashExactlyOnce() {
        List<String> disposed = new ArrayList<>();
        MirrorPrimaryProgramStash<String> stash = new MirrorPrimaryProgramStash<>(disposed::add);
        stash.store(new Object(), new Object(), "old-primary");

        stash.clear();
        stash.clear();

        assertEquals(List.of("old-primary"), disposed);
    }

    @Test
    void deleteWhileMirrorActiveTransfersPrimaryProgramsToOriginalDeletePath() {
        List<String> disposed = new ArrayList<>();
        MirrorPrimaryProgramStash<String> stash = new MirrorPrimaryProgramStash<>(disposed::add);
        stash.store(new Object(), new Object(), "primary");

        String originalDeleteShadersInput = stash.drain();
        disposed.add(originalDeleteShadersInput);
        stash.clear();

        assertEquals(List.of("primary"), disposed);
        assertTrue(stash.isEmpty());
    }

    @Test
    void vertexTypeChangeDisposesTheStalePrimaryMap() {
        List<String> disposed = new ArrayList<>();
        MirrorPrimaryProgramStash<String> stash = new MirrorPrimaryProgramStash<>(disposed::add);
        Object owner = new Object();
        stash.store(owner, new Object(), "old-format");

        assertNull(stash.take(owner, new Object()));
        assertEquals(List.of("old-format"), disposed);
    }
}
