package com.mirror.client;

import com.mirror.common.MirrorBlockEntity;
import com.mirror.config.MirrorConfig;
import com.mirror.compat.GrassFrame;
import com.mirror.compat.MirrorViewResources;
import com.mirror.compat.ThirdPartyFrame;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owns cached surfaces and schedules captures belonging to this outer frame's visible roots. */
public final class MirrorTextureManager {
    // Direct views are visible again as soon as the player turns back to a mirror. Keeping their
    // composed surface longer avoids a cold surface/pipeline handoff after a short trip away; the
    // capture pool still expires its heavyweight targets at the original 600-frame boundary.
    private static final int STALE_VIEW_GRACE_FRAMES = 1800;

    private static final Map<MirrorTextureKey, MirrorReflectionTexture> TEXTURES = new HashMap<>();
    private static final Map<MirrorTextureKey, Pending> PENDING = new HashMap<>();
    private static final Map<MirrorTextureKey, Long> LAST_USED_FRAME = new HashMap<>();
    private static final Set<UUID> VISIBLE_ROOTS = new HashSet<>();
    private static Set<UUID> activeRoots = Set.of();
    private static ClientLevel demandLevel;
    private static final List<TextureTarget> RETIRED_SURFACES = new ArrayList<>();
    private static long frameIndex;
    private static int recursiveViewCount;

    private MirrorTextureManager() {
    }

    public static void beginOuterFrame() {
        ClientLevel level = Minecraft.getInstance().level;
        if (demandLevel != level) {
            clear();
            demandLevel = level;
        }
        frameIndex++;
        VISIBLE_ROOTS.clear();
        MirrorDiagnostics.beginOuterFrame(frameIndex);
        ThirdPartyFrame.begin();
    }

    /** Schedules the direct, player-view texture for a mirror. */
    public static MirrorReflectionTexture request(MirrorBlockEntity mirror, double projectedPixels) {
        MirrorDiagnostics.recordRequest(false);
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        double maximumDistance = MirrorConfig.CLIENT.renderDistance.get();
        if (mirror.distanceToRenderBoundsSqr(camera.getPosition()) > maximumDistance * maximumDistance) {
            MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.DISTANCE);
            return null;
        }
        MirrorTextureKey key = new MirrorTextureKey(mirror.getId(), List.of(), 0);
        MirrorReflectionTexture texture = getOrCreate(key, mirror, projectedPixels,
                MirrorConfig.CLIENT.resolutionScale.get() / 8.0);
        if (texture == null) return null;
        VISIBLE_ROOTS.add(mirror.getId());
        markUsed(key);
        enqueue(key, mirror, List.of(), Set.of(mirror.getId()), projectedPixels);
        return texture.hasRendered() ? texture : null;
    }

    /** Returns or schedules the direct texture used by SHARED nested rendering. */
    public static MirrorReflectionTexture requestShared(MirrorBlockEntity mirror, double projectedPixels) {
        MirrorDiagnostics.recordRequest(true);
        MirrorTextureKey key = new MirrorTextureKey(mirror.getId(), List.of(), 0);
        MirrorReflectionTexture texture = getOrCreate(key, mirror, projectedPixels, 1.0);
        if (texture == null) return null;
        markUsed(key);
        if (!texture.hasRendered()) {
            enqueue(key, mirror, List.of(), activeRoots, projectedPixels);
        }
        return texture.hasRendered() ? texture : null;
    }

    /** Schedules a texture for one recursive parent chain. */
    public static MirrorReflectionTexture requestRecursive(MirrorBlockEntity mirror, double projectedPixels) {
        MirrorDiagnostics.recordRequest(true);
        int depth = MirrorLevelRenderer.getChildDepth();
        MirrorDiagnostics.recordRecursiveRequest(depth);
        // recursionDepth is zero-based: the direct mirror pass is depth 0, so a child at
        // depth 1 is already the second visible reflection. Treat maxRecursionDepth as the
        // user-facing total reflection count: 1 = direct only, 2 = one mirror-in-mirror, etc.
        if (depth >= MirrorConfig.CLIENT.maxRecursionDepth.get()) {
            MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.DEPTH);
            return null;
        }

        List<MirrorLevelRenderer.ReflectionPlane> reflectionPath =
                MirrorLevelRenderer.getChildReflectionPath();
        double minPixels = MirrorConfig.CLIENT.recursiveCullMinPixels.get();
        if (projectedPixels < minPixels * minPixels) {
            MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.PIXELS);
            return null;
        }

        List<UUID> parentChain = MirrorLevelRenderer.getChildParentChain();
        MirrorTextureKey key = new MirrorTextureKey(mirror.getId(), parentChain, depth);
        // Parent pixels already include its density/decay. Apply decay once on this edge.
        MirrorReflectionTexture texture = getOrCreate(key, mirror, projectedPixels,
                MirrorConfig.CLIENT.recursiveResolutionDecay.get());
        if (texture == null) return null;
        markUsed(key);
        enqueue(key, mirror, reflectionPath, activeRoots, projectedPixels);
        return texture.hasRendered() ? texture : null;
    }

    /**
     * Consumes requests collected by the completed outer world frame using that frame's camera and
     * tick delta. GameRenderer invokes this only after its outer renderLevel call has returned, so
     * reflected LevelRenderer passes are sequential off-screen renders rather than nested world-render
     * re-entry. Requests produced by those captures remain queued for the following outer frame.
     */
    public static void processPending(Camera camera, float partialTick) {
        long batchStart = MirrorDiagnostics.startTimer();
        int gpuSlot = -1;
        try {
            MirrorViewHistory.beginFrame();
            OculusCompat.beginMirrorFrame();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || camera == null) {
                clear();
                return;
            }
            discardInactiveRequests(minecraft.level);
            evictStaleViews();
            MirrorDiagnostics.recordPending(PENDING.size());
            if (PENDING.isEmpty()) return;

            gpuSlot = MirrorDiagnostics.beginGpuBatch();
            long saveStart = MirrorDiagnostics.startTimer();
            MirrorRenderState outerRenderState = MirrorRenderState.capture();
            MirrorDiagnostics.finishTimer(MirrorDiagnostics.Stage.STATE_SAVE, saveStart);
            try {
                renderPending(minecraft, camera, partialTick);
            } finally {
                long restoreStart = MirrorDiagnostics.startTimer();
                try {
                    outerRenderState.restore();
                } finally {
                    MirrorDiagnostics.finishTimer(MirrorDiagnostics.Stage.STATE_RESTORE, restoreStart);
                }
            }
        } finally {
            MirrorDiagnostics.endGpuBatch(gpuSlot);
            MirrorDiagnostics.finishTimer(MirrorDiagnostics.Stage.BATCH_TOTAL, batchStart);
            GrassFrame.finish();
            closeRetiredSurfaces();
            MirrorCapturePool.evictUnused();
            ThirdPartyFrame.report();
            MirrorViewResources.reportMemory();
            MirrorDiagnostics.endOuterFrame(VISIBLE_ROOTS.size(), PENDING.size());
        }
    }

    private static void enqueue(MirrorTextureKey key, MirrorBlockEntity mirror,
                                List<MirrorLevelRenderer.ReflectionPlane> parentPath,
                                Set<UUID> roots, double projectedPixels) {
        Pending previous = PENDING.get(key);
        Set<UUID> owners = new HashSet<>(roots);
        if (previous != null) owners.addAll(previous.roots());
        PENDING.put(key, new Pending(key, mirror, parentPath, owners,
                previous == null ? projectedPixels : Math.max(projectedPixels, previous.projectedPixels())));
    }

    private static void discardInactiveRequests(ClientLevel level) {
        var iterator = PENDING.values().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            pending.roots().retainAll(VISIBLE_ROOTS);
            MirrorDiagnostics.Rejection reason = null;
            if (pending.roots().isEmpty()) {
                reason = MirrorDiagnostics.Rejection.NO_ROOT;
            } else if (pending.mirror().isRemoved() || pending.mirror().getLevel() != level) {
                reason = MirrorDiagnostics.Rejection.REMOVED;
            } else if (pending.key().depth() > 0
                    && (MirrorConfig.CLIENT.recursionMode.get() != MirrorConfig.RecursionMode.RECURSIVE
                    || pending.key().depth() >= MirrorConfig.CLIENT.maxRecursionDepth.get())) {
                reason = MirrorDiagnostics.Rejection.DEPTH;
            } else if (pending.key().depth() == 0 && !VISIBLE_ROOTS.contains(pending.key().mirrorId())
                    && MirrorConfig.CLIENT.recursionMode.get() != MirrorConfig.RecursionMode.SHARED) {
                reason = MirrorDiagnostics.Rejection.DEPTH;
            }
            if (reason != null) {
                iterator.remove();
                MirrorDiagnostics.reject(reason);
            }
        }
    }

    private static void renderPending(Minecraft minecraft, Camera camera, float partialTick) {
        Vec3 mainEye = camera.getPosition().add(MirrorLevelRenderer.getMainBobEyeOffset());
        List<Pending> pending = new ArrayList<>(PENDING.values());
        PENDING.clear();
        // Render deepest-first so a parent pass always samples a child surface composed earlier in
        // this same frame; that keeps every recursion depth at ~0 added-frame latency. The per-frame
        // budget then only defers recursive (depth > 0) views once the budget is exhausted, so the
        // direct (depth 0) mirror the player is looking at always stays fresh.
        pending.sort(Comparator.comparingInt((Pending value) -> value.key().depth()).reversed());

        long budgetNanos = (long) (MirrorConfig.CLIENT.reflectionFrameBudgetMs.get() * 1_000_000.0D);
        long frameStart = System.nanoTime();
        List<Pending> deferred = null;
        for (Pending value : pending) {
            if (value.key().depth() > 0
                    && budgetNanos > 0L && System.nanoTime() - frameStart >= budgetNanos) {
                if (deferred == null) deferred = new ArrayList<>();
                deferred.add(value);
                continue;
            }
            MirrorReflectionTexture texture = TEXTURES.get(value.key());
            if (texture == null) continue;
            Vec3 eye = MirrorLevelRenderer.resolveReflectionPath(mainEye, value.parentPath());
            if (eye != null) {
                MirrorDiagnostics.recordView(value.key(), value.roots(), value.projectedPixels());
                activeRoots = value.roots();
                try {
                    texture.render(minecraft.level, value.mirror(), eye, partialTick, value.parentPath());
                } finally {
                    activeRoots = Set.of();
                }
            } else {
                MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.PATH);
            }
        }
        if (deferred != null) {
            MirrorDiagnostics.recordDeferredViews(deferred.size());
            for (Pending value : deferred) {
                Pending refreshed = PENDING.get(value.key());
                if (refreshed == null) {
                    enqueue(value.key(), value.mirror(), value.parentPath(), value.roots(), value.projectedPixels());
                } else {
                    // Keep the path refreshed by this frame's parent capture.
                    refreshed.roots().addAll(value.roots());
                }
            }
        }
    }

    public static boolean isRenderingReflection() {
        return MirrorLevelRenderer.isRenderingReflection();
    }

    public static float fade(ResourceLocation location) {
        for (MirrorReflectionTexture texture : TEXTURES.values()) {
            if (texture.textureLocation().equals(location)) return texture.fade();
        }
        return 0.0f;
    }

    public static void clear() {
        // Mirror textures/capture targets are view-owned. Oculus mirror pipelines are deliberately
        // not destroyed here; PipelineManager.destroyPipeline is their single generation owner.
        PENDING.clear();
        VISIBLE_ROOTS.clear();
        activeRoots = Set.of();
        demandLevel = null;
        LAST_USED_FRAME.clear();
        TEXTURES.values().forEach(MirrorReflectionTexture::close);
        TEXTURES.clear();
        closeRetiredSurfaces();
        recursiveViewCount = 0;
        frameIndex = 0L;
        MirrorCapturePool.clear();
        MirrorLevelRenderer.clearContext();
        GrassFrame.clear();
        MirrorViewResources.clear();
        MirrorDiagnostics.clear();
    }

    private static MirrorReflectionTexture getOrCreate(MirrorTextureKey key, MirrorBlockEntity mirror,
                                                       double projectedPixels, double density) {
        if (!Double.isFinite(projectedPixels) || projectedPixels <= 0.0) {
            MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.OFFSCREEN);
            return null;
        }
        MirrorReflectionTexture created = TEXTURES.get(key);
        if (created == null) {
            // R0 hard cap: bound the chain-isolated recursive texture set. Direct (depth 0) views
            // are never capped; only new mirror-in-mirror chains are truncated once the cap is hit.
            if (key.depth() > 0
                    && recursiveViewCount >= MirrorConfig.CLIENT.maxRecursiveViews.get()) {
                MirrorDiagnostics.reject(MirrorDiagnostics.Rejection.CAPACITY);
                return null;
            }
            created = new MirrorReflectionTexture(key.depth(), key.parentChain());
            if (key.depth() > 0) recursiveViewCount++;
            TEXTURES.put(key, created);
        }
        created.requestSize(frameIndex, projectedPixels,
                (double) mirror.getScreenPixelWidth() / mirror.getScreenPixelHeight(), density);
        return created;
    }

    private static void markUsed(MirrorTextureKey key) {
        LAST_USED_FRAME.put(key, frameIndex);
    }

    private static void evictStaleViews() {
        if (TEXTURES.isEmpty()) return;
        boolean evicted = false;
        java.util.Iterator<Map.Entry<MirrorTextureKey, MirrorReflectionTexture>> iterator =
                TEXTURES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MirrorTextureKey, MirrorReflectionTexture> entry = iterator.next();
            MirrorTextureKey key = entry.getKey();
            if (PENDING.containsKey(key)) continue;
            long lastUsed = LAST_USED_FRAME.getOrDefault(key, frameIndex);
            if (frameIndex - lastUsed <= STALE_VIEW_GRACE_FRAMES) continue;

            iterator.remove();
            LAST_USED_FRAME.remove(key);
            if (key.depth() > 0) recursiveViewCount--;
            entry.getValue().close();
            evicted = true;
        }

        // Surface views are transient, but a compiled Oculus pipeline is shader-generation owned.
        // Releasing the lightweight capture targets here is safe; warmed shader/terrain programs
        // remain available when a mirror using the same slot becomes visible again.
        if (evicted && TEXTURES.isEmpty() && PENDING.isEmpty()) {
            MirrorCapturePool.clear();
        }
    }

    private record Pending(MirrorTextureKey key, MirrorBlockEntity mirror,
                           List<MirrorLevelRenderer.ReflectionPlane> parentPath,
                           Set<UUID> roots, double projectedPixels) {
        private Pending {
            parentPath = List.copyOf(parentPath);
        }
    }

    static void retireSurface(TextureTarget target) {
        RETIRED_SURFACES.add(target);
    }

    private static void closeRetiredSurfaces() {
        // Wait until both reflection and deferred-maintenance GL snapshots have been restored.
        for (TextureTarget target : RETIRED_SURFACES) {
            MirrorDiagnostics.recordResource("SURFACE_FREE", -1, target.width, target.height);
            target.destroyBuffers();
        }
        RETIRED_SURFACES.clear();
    }
}
