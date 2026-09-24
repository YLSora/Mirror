package com.mirror.compat;

import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPassContext;
import com.mirror.client.MirrorRenderState;
import com.mirror.compat.mixin.GrassCacheAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Collects this frame's real views before spending Grassier Grass's single build budget. */
public final class GrassFrame {
    private static final List<Demand> DEMANDS = new ArrayList<>();
    private static final List<Demand> LOD_DEMANDS = new ArrayList<>();
    private static final Frustum UNION = new Frustum(new Matrix4f(), new Matrix4f()) {
        @Override public boolean isVisible(AABB bounds) {
            for (Demand demand : DEMANDS) {
                if (demand.frustum == null || demand.frustum.isVisible(bounds)) return true;
            }
            return false;
        }
    };
    private static Parameters parameters;
    private static ClientLevel level;
    private static boolean maintaining;
    private static boolean lodChanged;

    private GrassFrame() { }

    public static void begin() {
        DEMANDS.clear();
        parameters = null;
        level = null;
        maintaining = false;
    }

    public static void clear() {
        begin();
        LOD_DEMANDS.clear();
    }

    public static boolean maintaining() { return maintaining; }

    public static boolean recheckLod() { return lodChanged; }

    public static void collect(ClientLevel world, int x, int y, int z, int radius, int vertical,
                               Vec3 eye, long tick, boolean iris, boolean compute,
                               TextureAtlasSprite blade, TextureAtlasSprite snow, Frustum frustum) {
        if (ThirdPartyFrame.auxiliary()) return;
        if (level != null && level != world) clear();
        level = world;
        if (MirrorPassContext.isActive()) {
            Vec3 origin = MirrorPassContext.current().cullingOrigin();
            x = SectionPos.blockToSectionCoord(origin.x);
            y = SectionPos.blockToSectionCoord(origin.y);
            z = SectionPos.blockToSectionCoord(origin.z);
        }
        DEMANDS.add(new Demand(x, y, z, radius, vertical, eye,
                frustum == null ? null : new Frustum(frustum)));
        // Shader/mesh format comes from the main view, never a transient shadow pipeline.
        if (!MirrorPassContext.isActive()) {
            parameters = new Parameters(tick, iris, compute, blade, snow, eye);
        }
        ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_DRAW);
    }

    public static boolean contains(long key) {
        int x = SectionPos.x(key), y = SectionPos.y(key), z = SectionPos.z(key);
        if (level == null || y < level.getMinSection() || y >= level.getMaxSection()
                || !level.getChunkSource().hasChunk(x, z)) return false;
        for (Demand demand : DEMANDS) {
            if (demand.contains(x, y, z)) return true;
        }
        return false;
    }

    public static double distanceSquared(long key, boolean horizontal) {
        double best = Double.POSITIVE_INFINITY;
        for (Demand demand : DEMANDS) {
            best = Math.min(best, distanceSquared(key, demand.eye, horizontal));
        }
        return best;
    }

    public static Vec3 lodEye(long key) {
        Vec3 best = parameters.eye;
        double distance = Double.POSITIVE_INFINITY;
        for (Demand demand : DEMANDS) {
            double candidate = distanceSquared(key, demand.eye, true);
            if (candidate < distance) {
                distance = candidate;
                best = demand.eye;
            }
        }
        return best;
    }

    private static double distanceSquared(long key, Vec3 eye, boolean horizontal) {
        double x = SectionPos.sectionToBlockCoord(SectionPos.x(key)) + 8 - eye.x;
        double y = horizontal ? 0.0 : SectionPos.sectionToBlockCoord(SectionPos.y(key)) + 8 - eye.y;
        double z = SectionPos.sectionToBlockCoord(SectionPos.z(key)) + 8 - eye.z;
        return x * x + y * y + z * z;
    }

    /** Runs after captures, so even mirror-only grass participates without per-view maintenance. */
    public static void finish() {
        Minecraft minecraft = Minecraft.getInstance();
        if (parameters == null || level != minecraft.level || minecraft.player == null) {
            begin();
            return;
        }
        Parameters args = parameters;
        lodChanged = DEMANDS.size() != LOD_DEMANDS.size();
        for (int i = 0; !lodChanged && i < DEMANDS.size(); i++) {
            Demand current = DEMANDS.get(i), previous = LOD_DEMANDS.get(i);
            lodChanged = current.radius != previous.radius || current.x != previous.x
                    || current.y != previous.y || current.z != previous.z
                    || current.eye.distanceToSqr(previous.eye) >= 16.0;
        }
        // Enumerate only the physical client chunk envelope, not distant virtual mirror eyes.
        int loadedRadius = minecraft.options.getEffectiveRenderDistance() + 3;
        int playerX = SectionPos.blockToSectionCoord(minecraft.player.getX());
        int playerZ = SectionPos.blockToSectionCoord(minecraft.player.getZ());
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Demand demand : DEMANDS) {
            minX = Math.min(minX, demand.x - demand.radius);
            maxX = Math.max(maxX, demand.x + demand.radius);
            minY = Math.min(minY, demand.y - demand.vertical);
            maxY = Math.max(maxY, demand.y + demand.vertical);
            minZ = Math.min(minZ, demand.z - demand.radius);
            maxZ = Math.max(maxZ, demand.z + demand.radius);
        }
        minX = Math.max(minX, playerX - loadedRadius);
        maxX = Math.min(maxX, playerX + loadedRadius);
        minZ = Math.max(minZ, playerZ - loadedRadius);
        maxZ = Math.min(maxZ, playerZ + loadedRadius);
        minY = Math.max(minY, level.getMinSection());
        maxY = Math.min(maxY, level.getMaxSection() - 1);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            begin();
            return;
        }
        int x = Math.floorDiv(minX + maxX, 2), y = Math.floorDiv(minY + maxY, 2);
        int z = Math.floorDiv(minZ + maxZ, 2);
        int radius = Math.max(maxX - x, maxZ - z), vertical = maxY - y;
        long start = MirrorDiagnostics.startTimer();
        MirrorRenderState saved = MirrorRenderState.capture();
        maintaining = true;
        try {
            GrassCacheAccess.mirror$drainDirty();
            GrassCacheAccess.mirror$evict(x, y, z, radius, vertical);
            GrassCacheAccess.mirror$build(level, x, y, z, radius, vertical, args.eye,
                    args.tick, args.iris, args.compute, args.blade, args.snow, UNION);
            ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_MAINTENANCE);
            if (lodChanged) {
                LOD_DEMANDS.clear();
                LOD_DEMANDS.addAll(DEMANDS);
            }
        } finally {
            maintaining = false;
            saved.restore();
            MirrorDiagnostics.finishTimer(MirrorDiagnostics.Stage.THIRD_PARTY_MAINTENANCE, start);
            begin();
        }
    }

    private record Parameters(long tick, boolean iris, boolean compute, TextureAtlasSprite blade,
                              TextureAtlasSprite snow, Vec3 eye) { }

    private record Demand(int x, int y, int z, int radius, int vertical, Vec3 eye, Frustum frustum) {
        boolean contains(int sx, int sy, int sz) {
            long dx = (long) sx - x, dz = (long) sz - z;
            return Math.abs((long) sy - y) <= vertical && dx * dx + dz * dz <= (long) radius * radius;
        }
    }
}
