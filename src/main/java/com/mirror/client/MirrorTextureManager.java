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
    private static final Map<MirrorTextureKey, MirrorReflectionTexture> TEXTURES = new HashMap<>();
    private static final Map<MirrorTextureKey, Pending> PENDING = new HashMap<>();

    private MirrorTextureManager() {
    }

    /** Schedules the direct, player-view texture for a mirror. */
    public static MirrorReflectionTexture request(MirrorBlockEntity mirror) {
        MirrorTextureKey key = directKey(mirror);
        MirrorReflectionTexture texture = getOrCreate(key, mirror);
        if (texture == null) return null;
        PENDING.put(key, new Pending(key, mirror, List.of()));
        return texture.hasRendered() ? texture : null;
    }

    /** Returns or schedules the direct texture used by SHARED nested rendering. */
    public static MirrorReflectionTexture requestShared(MirrorBlockEntity mirror) {
        MirrorTextureKey key = directKey(mirror);
        MirrorReflectionTexture texture = getOrCreate(key, mirror);
        if (texture == null) return null;
        if (!texture.hasRendered()) {
            PENDING.put(key, new Pending(key, mirror, List.of()));
        }
        return texture.hasRendered() ? texture : null;
    }

    /** Schedules a texture for one recursive parent chain. */
    public static MirrorReflectionTexture requestRecursive(MirrorBlockEntity mirror) {
        int depth = MirrorLevelRenderer.getChildDepth();
        // recursionDepth is zero-based: the direct mirror pass is depth 0, so a child at
        // depth 1 is already the second visible reflection. Treat maxRecursionDepth as the
        // user-facing total reflection count: 1 = direct only, 2 = one mirror-in-mirror, etc.
        if (depth >= MirrorConfig.CLIENT.maxRecursionDepth.get()) return null;

        List<UUID> parentChain = MirrorLevelRenderer.getChildParentChain();
        int[] dimensions = recursiveDimensions(mirror, depth);
        MirrorTextureKey key = new MirrorTextureKey(mirror.getId(), parentChain, depth,
                dimensions[0], dimensions[1]);
        MirrorReflectionTexture texture = getOrCreate(key, mirror);
        if (texture == null) return null;
        PENDING.put(key, new Pending(key, mirror, MirrorLevelRenderer.getChildReflectionPath()));
        return texture.hasRendered() ? texture : null;
    }

    /**
     * Consumes requests from the previous outer frame with the current frame's camera and tick delta.
     * This is invoked from LevelRenderer immediately before its normal framebuffer clear, after Oculus
     * has established the current frame's temporal uniforms but before the outer shader pipeline begins.
     */
    public static void processPending(Camera camera, float partialTick) {
        if (PENDING.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel) || camera == null) {
            clear();
            return;
        }

        Vec3 mainEye = camera.getPosition().add(MirrorLevelRenderer.getMainBobEyeOffset());
        List<Pending> pending = new ArrayList<>(PENDING.values());
        PENDING.clear();
        // A parent capture samples completed child surfaces. Refresh the deepest textures first
        // so depth 0 never races a child discovered during the previous frame.
        pending.sort(Comparator.comparingInt(
                (Pending value) -> value.key().depth()).reversed());
        for (Pending value : pending) {
            MirrorReflectionTexture texture = TEXTURES.get(value.key());
            if (texture == null) continue;
            Vec3 eye = MirrorLevelRenderer.resolveReflectionPath(mainEye, value.parentPath());
            if (eye != null) {
                texture.render(minecraft.level, value.mirror(), eye, partialTick, value.parentPath());
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
        OculusCompat.clearMirrorPipelines();
        PENDING.clear();
        TEXTURES.values().forEach(MirrorReflectionTexture::close);
        TEXTURES.clear();
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
        double scale = MirrorConfig.CLIENT.resolutionScale.get()
                * Math.pow(MirrorConfig.CLIENT.recursiveResolutionDecay.get(), depth);
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
            if (oldTexture.reuseForChangedLayout(key.width(), key.height(),
                    mirror.getScreenPixelWidth(), mirror.getScreenPixelHeight())) {
                reusable = oldTexture;
            } else {
                oldTexture.close();
            }
            break;
        }
        if (reusable != null) {
            TEXTURES.put(key, reusable);
            return reusable;
        }
        return TEXTURES.computeIfAbsent(key, ignored -> new MirrorReflectionTexture(
                key.width(), key.height(), mirror.getScreenPixelWidth(), mirror.getScreenPixelHeight(),
                key.depth(), key.parentChain()));
    }

    private record Pending(MirrorTextureKey key, MirrorBlockEntity mirror,
                           List<MirrorLevelRenderer.ReflectionPlane> parentPath) {
        private Pending {
            parentPath = List.copyOf(parentPath);
        }
    }
}
