package com.mirror.client;

import com.mirror.common.MirrorBlockEntity;
import com.mirror.config.MirrorConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns the render-target cache and defers all off-screen passes until the BE pass is complete. */
public final class MirrorTextureManager {
    private static final Map<MirrorTextureKey, MirrorReflectionTexture> TEXTURES = new HashMap<>();
    private static final Map<MirrorTextureKey, Pending> PENDING = new HashMap<>();

    private MirrorTextureManager() {
    }

    /** Schedules the direct, player-view texture for a mirror. */
    public static MirrorReflectionTexture request(MirrorBlockEntity mirror, Vec3 eye, float partialTick) {
        MirrorTextureKey key = directKey(mirror);
        MirrorReflectionTexture texture = getOrCreate(key);
        if (texture == null) return null;
        PENDING.put(key, new Pending(key, mirror, eye, partialTick));
        return texture.hasRendered() ? texture : null;
    }

    /** Returns or schedules the direct texture used by SHARED nested rendering. */
    public static MirrorReflectionTexture requestShared(MirrorBlockEntity mirror, float partialTick) {
        MirrorTextureKey key = directKey(mirror);
        MirrorReflectionTexture texture = getOrCreate(key);
        if (texture == null) return null;
        if (!texture.hasRendered()) {
            Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            PENDING.put(key, new Pending(key, mirror, camera.getPosition(), partialTick));
        }
        return texture.hasRendered() ? texture : null;
    }

    /** Schedules a texture for one recursive parent chain. */
    public static MirrorReflectionTexture requestRecursive(MirrorBlockEntity mirror, Vec3 eye,
                                                            float partialTick) {
        int depth = MirrorLevelRenderer.getChildDepth();
        if (depth > MirrorConfig.CLIENT.maxRecursionDepth.get()) return null;

        List<UUID> parentChain = MirrorLevelRenderer.getChildParentChain();
        int[] dimensions = recursiveDimensions(mirror, depth);
        MirrorTextureKey key = new MirrorTextureKey(mirror.getId(), parentChain, depth,
                dimensions[0], dimensions[1]);
        MirrorReflectionTexture texture = getOrCreate(key);
        if (texture == null) return null;
        PENDING.put(key, new Pending(key, mirror, eye, partialTick));
        return texture.hasRendered() ? texture : null;
    }

    public static void processPending() {
        if (PENDING.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel)) {
            clear();
            return;
        }

        List<Pending> pending = new ArrayList<>(PENDING.values());
        PENDING.clear();
        MirrorLevelRenderer.setRenderingReflection(true);
        try {
            for (Pending value : pending) {
                MirrorReflectionTexture texture = TEXTURES.get(value.key());
                if (texture != null) {
                    texture.render(minecraft.level, value.mirror(), value.eye(), value.partialTick());
                }
            }
        } finally {
            MirrorLevelRenderer.setRenderingReflection(false);
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
        PENDING.clear();
        TEXTURES.values().forEach(MirrorReflectionTexture::close);
        TEXTURES.clear();
        MirrorLevelRenderer.clearContext();
    }

    private static MirrorTextureKey directKey(MirrorBlockEntity mirror) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        double distance = camera.getPosition().distanceTo(Vec3.atCenterOf(mirror.getBlockPos()));
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

    private static MirrorReflectionTexture getOrCreate(MirrorTextureKey key) {
        if (key.width() <= 0 || key.height() <= 0) return null;
        return TEXTURES.computeIfAbsent(key,
                ignored -> new MirrorReflectionTexture(key.width(), key.height(), key.depth(), key.parentChain()));
    }

    private record Pending(MirrorTextureKey key, MirrorBlockEntity mirror, Vec3 eye, float partialTick) {
    }
}
