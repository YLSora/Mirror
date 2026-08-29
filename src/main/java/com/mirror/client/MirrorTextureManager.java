package com.mirror.client;

import com.mirror.common.MirrorBlockEntity;
import com.mirror.config.MirrorConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns the render-target cache and defers all off-screen passes until the next outer world frame. */
public final class MirrorTextureManager {
    private static final int STALE_VIEW_GRACE_FRAMES = 600;

    private static final Map<MirrorTextureKey, MirrorReflectionTexture> TEXTURES = new HashMap<>();
    private static final Map<MirrorTextureKey, Pending> PENDING = new HashMap<>();
    private static final Map<MirrorTextureKey, Long> LAST_USED_FRAME = new HashMap<>();
    /**
     * Views whose layout grew beyond their surface target's capacity. Their GL buffers are destroyed
     * only after the replacement view has rendered its first frame, so a shader pipeline or a
     * deferred surface that still references the old target during the resolution transition never
     * touches a destroyed texture object.
     */
    private static final List<MirrorReflectionTexture> RETIRED = new ArrayList<>();
    private static long frameIndex;
    private static int recursiveViewCount;

    private MirrorTextureManager() {
    }

    /** Schedules the direct, player-view texture for a mirror. */
    public static MirrorReflectionTexture request(MirrorBlockEntity mirror) {
        MirrorTextureKey key = directKey(mirror);
        MirrorReflectionTexture texture = getOrCreate(key, mirror);
        if (texture == null) return null;
        markUsed(key);
        PENDING.put(key, new Pending(key, mirror, List.of()));
        return texture.hasRendered() ? texture : null;
    }

    /** Returns or schedules the direct texture used by SHARED nested rendering. */
    public static MirrorReflectionTexture requestShared(MirrorBlockEntity mirror) {
        MirrorTextureKey key = directKey(mirror);
        MirrorReflectionTexture texture = getOrCreate(key, mirror);
        if (texture == null) return null;
        markUsed(key);
        if (!texture.hasRendered()) {
            PENDING.put(key, new Pending(key, mirror, List.of()));
        }
        return texture.hasRendered() ? texture : null;
    }

    /** Schedules a texture for one recursive parent chain. */
    public static MirrorReflectionTexture requestRecursive(MirrorBlockEntity mirror) {
        int depth = MirrorLevelRenderer.getChildDepth();
        MirrorDiagnostics.recordRecursiveRequest(depth);
        // recursionDepth is zero-based: the direct mirror pass is depth 0, so a child at
        // depth 1 is already the second visible reflection. Treat maxRecursionDepth as the
        // user-facing total reflection count: 1 = direct only, 2 = one mirror-in-mirror, etc.
        if (depth >= MirrorConfig.CLIENT.maxRecursionDepth.get()) return null;

        // The recursion is bounded by maxRecursionDepth (the depth check above) and, for a real
        // mirror tunnel, by the sub-pixel cull below: each extra reflection is geometrically
        // smaller, so the chain naturally dies out. No deep/convergence collapse is applied, which
        // previously capped the visible mirror-in-mirror count at depth 2 regardless of the
        // configured maxRecursionDepth.
        List<MirrorLevelRenderer.ReflectionPlane> reflectionPath =
                MirrorLevelRenderer.getChildReflectionPath();
        // Rendering-principle cull: a child mirror only needs its own reflection when it is large
        // enough inside the parent mirror to be seen. Its apparent width is its screen width over
        // its distance to the parent plane; below the threshold its reflection is sub-pixel, so
        // dropping the chain here prunes the combinatorial recursion tree at its source instead of
        // letting every distant mirror spawn its own mirror-in-mirror subtree.
        double minPixels = MirrorConfig.CLIENT.recursiveCullMinPixels.get();
        if (minPixels > 0.0 && !reflectionPath.isEmpty()) {
            MirrorLevelRenderer.ReflectionPlane parentPlane =
                    reflectionPath.get(reflectionPath.size() - 1);
            double distance = Math.abs(Vec3.atCenterOf(mirror.getBlockPos())
                    .subtract(parentPlane.point()).dot(parentPlane.normal()));
            double apparentWidth = mirror.getScreenPixelWidth() / Math.max(1.0, distance);
            if (apparentWidth < minPixels) return null;
        }

        List<UUID> parentChain = MirrorLevelRenderer.getChildParentChain();
        int[] dimensions = recursiveDimensions(mirror, depth);
        MirrorTextureKey key = new MirrorTextureKey(mirror.getId(), parentChain, depth,
                dimensions[0], dimensions[1]);
        MirrorReflectionTexture texture = getOrCreate(key, mirror);
        if (texture == null) return null;
        markUsed(key);
        PENDING.put(key, new Pending(key, mirror, reflectionPath));
        return texture.hasRendered() ? texture : null;
    }

    /**
     * Consumes requests collected by the completed outer world frame using that frame's camera and
     * tick delta. GameRenderer invokes this only after its outer renderLevel call has returned, so
     * reflected LevelRenderer passes are sequential off-screen renders rather than nested world-render
     * re-entry. Requests produced by those captures remain queued for the following outer frame.
     */
    public static void processPending(Camera camera, float partialTick) {
        frameIndex++;
        MirrorViewHistory.beginFrame();
        OculusCompat.beginMirrorFrame();
        MirrorDiagnostics.beginOuterFrame(PENDING.size());
        evictStaleViews();
        if (PENDING.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel) || camera == null) {
            clear();
            return;
        }

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
                texture.render(minecraft.level, value.mirror(), eye, partialTick, value.parentPath());
            }
        }
        if (deferred != null) {
            MirrorDiagnostics.recordDeferredViews(deferred.size());
            for (Pending value : deferred) {
                PENDING.put(value.key(), value);
            }
        }
        // Views retired by this frame's layout growth can now be destroyed: their replacement view
        // has either rendered its first frame or has been deferred with the old target still valid.
        closeRetired();
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
        LAST_USED_FRAME.clear();
        TEXTURES.values().forEach(MirrorReflectionTexture::close);
        TEXTURES.clear();
        closeRetired();
        recursiveViewCount = 0;
        frameIndex = 0L;
        MirrorCapturePool.clear();
        MirrorLevelRenderer.clearContext();
    }

    private static MirrorTextureKey directKey(MirrorBlockEntity mirror) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        double distance = Math.sqrt(mirror.distanceToRenderBoundsSqr(camera.getPosition()));
        int[] dimensions = directDimensions(mirror, distance);
        return new MirrorTextureKey(mirror.getId(), List.of(), 0, dimensions[0], dimensions[1]);
    }

    private static int[] directDimensions(MirrorBlockEntity mirror, double distance) {
        if (distance > MirrorConfig.CLIENT.renderDistance.get()) return new int[]{0, 0};
        double lodScale = distance <= 24.0 ? 1.0 : distance <= 40.0 ? 0.5 : 0.25;
        double scale = MirrorConfig.CLIENT.resolutionScale.get() * lodScale;
        return new int[]{
                Math.max(1, (int) Math.round(mirror.getScreenPixelWidth() * scale)),
                Math.max(1, (int) Math.round(mirror.getScreenPixelHeight() * scale))
        };
    }

    private static int[] recursiveDimensions(MirrorBlockEntity mirror, int depth) {
        // Depth 1 is the visible first mirror-in-mirror, so keep it at full resolution for
        // crispness. Apply the configured decay to every independently rendered deeper level.
        double scale = MirrorConfig.CLIENT.resolutionScale.get()
                * Math.pow(MirrorConfig.CLIENT.recursiveResolutionDecay.get(), Math.max(0, depth - 1));
        return new int[]{
                Math.max(1, (int) Math.round(mirror.getScreenPixelWidth() * scale)),
                Math.max(1, (int) Math.round(mirror.getScreenPixelHeight() * scale))
        };
    }

    private static MirrorReflectionTexture getOrCreate(MirrorTextureKey key, MirrorBlockEntity mirror) {
        if (key.width() <= 0 || key.height() <= 0) return null;
        MirrorReflectionTexture reusable = null;
        java.util.Iterator<Map.Entry<MirrorTextureKey, MirrorReflectionTexture>> entries =
                TEXTURES.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<MirrorTextureKey, MirrorReflectionTexture> entry = entries.next();
            MirrorTextureKey oldKey = entry.getKey();
            boolean sameView = oldKey.mirrorId().equals(key.mirrorId())
                    && oldKey.depth() == key.depth()
                    && oldKey.parentChain().equals(key.parentChain());
            if (!sameView || oldKey.equals(key)) continue;

            MirrorReflectionTexture oldTexture = entry.getValue();
            entries.remove();
            PENDING.remove(oldKey);
            LAST_USED_FRAME.remove(oldKey);
            if (oldTexture.reuseForChangedLayout(key.width(), key.height(),
                    mirror.getScreenPixelWidth(), mirror.getScreenPixelHeight())) {
                reusable = oldTexture;
            } else {
                if (oldKey.depth() > 0) recursiveViewCount--;
                // Retire instead of closing here. The old surface target is replaced by a larger one
                // and its GL buffers must stay alive until the replacement view finishes its first
                // compose; destroying them immediately leaves the active pipeline sampling a freed
                // texture during the resolution-bucket switch (Oculus reports "invalid texture
                // object" / "y exceeds").
                RETIRED.add(oldTexture);
            }
            break;
        }
        if (reusable != null) {
            TEXTURES.put(key, reusable);
            return reusable;
        }
        MirrorReflectionTexture created = TEXTURES.get(key);
        if (created == null) {
            // R0 hard cap: bound the chain-isolated recursive texture set. Direct (depth 0) views
            // are never capped; only new mirror-in-mirror chains are truncated once the cap is hit.
            if (key.depth() > 0
                    && recursiveViewCount >= MirrorConfig.CLIENT.maxRecursiveViews.get()) {
                return null;
            }
            created = new MirrorReflectionTexture(
                    key.width(), key.height(), mirror.getScreenPixelWidth(), mirror.getScreenPixelHeight(),
                    key.depth(), key.parentChain());
            if (key.depth() > 0) recursiveViewCount++;
            TEXTURES.put(key, created);
        }
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

    /** Destroys the GL buffers of views retired by a layout growth, after their replacement rendered. */
    private static void closeRetired() {
        if (RETIRED.isEmpty()) return;
        for (MirrorReflectionTexture texture : RETIRED) {
            texture.close();
        }
        RETIRED.clear();
    }

    private record Pending(MirrorTextureKey key, MirrorBlockEntity mirror,
                           List<MirrorLevelRenderer.ReflectionPlane> parentPath) {
        private Pending {
            parentPath = List.copyOf(parentPath);
        }
    }
}
