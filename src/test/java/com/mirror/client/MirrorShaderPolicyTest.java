package com.mirror.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MirrorShaderPolicyTest {
    @Test
    void layerUsesFrameMaximumAndImmediateGrowthButDelayedShrink() {
        MirrorShaderPolicy policy = new MirrorShaderPolicy();
        policy.beginFrame();
        policy.requestSize(0, 256);
        policy.requestSize(0, 512);
        policy.finishFrame(0L, 5_000_000_000L);
        assertEquals(512, policy.activeSize(0));

        policy.beginFrame();
        policy.requestSize(0, 768);
        policy.finishFrame(1L, 5_000_000_000L);
        assertEquals(768, policy.activeSize(0));

        policy.beginFrame();
        policy.requestSize(0, 384);
        policy.finishFrame(2L, 5_000_000_000L);
        assertEquals(768, policy.activeSize(0));
        policy.beginFrame();
        policy.requestSize(0, 384);
        policy.finishFrame(5_000_000_002L, 5_000_000_000L);
        assertEquals(384, policy.activeSize(0));
    }

    @Test
    void secondCaptureBatchCanSealWithoutResettingTheTickBuildBudget() {
        MirrorShaderPolicy policy = new MirrorShaderPolicy();
        policy.beginFrame();
        assertTrue(policy.claimConstruction(0));
        policy.requestSize(0, 256);
        policy.finishFrame(0, 5);
        assertEquals(256, policy.activeSize(0));

        policy.requestSize(0, 512);
        policy.finishFrame(1, 5);
        assertEquals(512, policy.activeSize(0));
        assertFalse(policy.claimConstruction(0));
        assertFalse(policy.claimBuild(1));

        policy.requestSize(0, 256);
        policy.finishFrame(2, 5);
        policy.finishFrame(3, 5); // No second demand: do not restart the shrink timer.
        policy.beginFrame();
        policy.requestSize(0, 256);
        policy.finishFrame(7, 5);
        assertEquals(256, policy.activeSize(0));
    }

    @Test
    void invalidLayerDemandIsRejectedWithoutChangingCaptureSize() {
        MirrorShaderPolicy policy = new MirrorShaderPolicy();
        policy.beginFrame();
        policy.requestSize(0, 256);
        policy.requestSize(0, 0);
        policy.requestSize(-1, 1024);
        policy.finishFrame(0, 5);
        assertEquals(256, policy.activeSize(0));
        assertEquals(0, policy.activeSize(-1));
    }

    @Test
    void depthLimitReusesLastAvailableParentLayer() {
        assertEquals(2, MirrorShaderPolicy.maxActiveDepth(8, 2));
        assertTrue(MirrorShaderPolicy.reuseParent(7, 8, 2));
        assertTrue(MirrorShaderPolicy.reuseParent(1, 8, 1));
        assertFalse(MirrorShaderPolicy.reuseParent(1, 8, 2));
    }

    @Test
    void failuresCoolDownAndGiveUpUntilGenerationReset() {
        MirrorShaderPolicy policy = new MirrorShaderPolicy();
        long cooldown = 30_000_000_000L;
        assertTrue(policy.mayAttempt(1, 0));
        policy.failed(1, 0, cooldown);
        assertFalse(policy.mayAttempt(1, cooldown - 1));
        assertTrue(policy.mayAttempt(1, cooldown));
        policy.failed(1, cooldown, cooldown);
        policy.failed(1, cooldown * 2, cooldown);
        assertEquals(3, policy.failureCount(1));
        assertFalse(policy.mayAttempt(1, cooldown * 100));
        policy.resetGeneration();
        assertEquals(0, policy.failureCount(1));
        assertTrue(policy.mayAttempt(1, 0));
    }

    @Test
    void borrowedShadowFailureDisablesBorrowingUntilShaderGenerationReset() {
        MirrorShaderPolicy policy = new MirrorShaderPolicy();
        assertTrue(policy.mayBorrowShadowTargets());

        policy.shadowBorrowFailed("OutOfMemoryError");
        assertFalse(policy.mayBorrowShadowTargets());
        assertEquals("OutOfMemoryError", policy.shadowBorrowFailureClass());

        policy.activateProgramSet(new Object()); // A ProgramSet switch does not end the shader generation.
        assertFalse(policy.mayBorrowShadowTargets());
        assertEquals("OutOfMemoryError", policy.shadowBorrowFailureClass());

        policy.resetGeneration();
        assertTrue(policy.mayBorrowShadowTargets());
        assertNull(policy.shadowBorrowFailureClass());
    }

    @Test
    void equivalentProgramSetRetainsButReplacementAndLogoutResetState() {
        MirrorShaderPolicy policy = new MirrorShaderPolicy();
        Object setA = new Object();
        Object setB = new Object();
        assertTrue(policy.activateProgramSet(setA));
        policy.failed(0, 0, 30);
        assertFalse(policy.activateProgramSet(setA));
        assertEquals(1, policy.failureCount(0));
        assertTrue(policy.activateProgramSet(setB));
        assertEquals(1, policy.failureCount(0));
        policy.resetGeneration();
        assertEquals(0, policy.failureCount(0));
        policy.failed(0, 0, 30);
        policy.logout();
        assertEquals(1, policy.failureCount(0));
        assertTrue(policy.activateProgramSet(setA));
    }

    @Test
    void loadingAndDemandShareTheSameFrameBudgetAcrossLifecycleEvents() {
        MirrorShaderPolicy policy = new MirrorShaderPolicy();
        assertTrue(policy.claimConstruction(0));
        assertTrue(policy.claimBuild(0));
        policy.resetGeneration();
        assertFalse(policy.claimConstruction(0));
        assertFalse(policy.claimBuild(1));
        policy.beginFrame();
        assertTrue(policy.claimBuild(1));
        assertFalse(policy.claimBuild(0));
    }
    @Test
    void customTextureBorrowFailureIsIndependentAndOnlyShaderReloadResetsIt() {
        MirrorShaderPolicy policy = new MirrorShaderPolicy();
        assertTrue(policy.mayBorrowCustomTextures());

        policy.customTextureBorrowFailed("OutOfMemoryError");
        assertFalse(policy.mayBorrowCustomTextures());
        assertEquals("OutOfMemoryError", policy.customTextureBorrowFailureClass());
        assertTrue(policy.mayBorrowShadowTargets());

        policy.activateProgramSet(new Object());
        assertFalse(policy.mayBorrowCustomTextures());
        policy.shadowBorrowFailed("LinkageError");
        assertFalse(policy.mayBorrowShadowTargets());
        assertFalse(policy.mayBorrowCustomTextures());

        policy.resetGeneration();
        assertTrue(policy.mayBorrowCustomTextures());
        assertTrue(policy.mayBorrowShadowTargets());
        assertNull(policy.customTextureBorrowFailureClass());
        assertNull(policy.shadowBorrowFailureClass());
    }

    @Test
    void customImageBorrowFailureIsIndependentAndOnlyShaderReloadResetsIt() {
        MirrorShaderPolicy policy = new MirrorShaderPolicy();
        assertTrue(policy.mayBorrowCustomImages());

        policy.customImageBorrowFailed("OutOfMemoryError");
        assertFalse(policy.mayBorrowCustomImages());
        assertEquals("OutOfMemoryError", policy.customImageBorrowFailureClass());
        assertTrue(policy.mayBorrowShadowTargets());
        assertTrue(policy.mayBorrowCustomTextures());

        policy.activateProgramSet(new Object());
        policy.logout();
        assertFalse(policy.mayBorrowCustomImages());

        policy.resetGeneration();
        assertTrue(policy.mayBorrowCustomImages());
        assertNull(policy.customImageBorrowFailureClass());
    }

}
