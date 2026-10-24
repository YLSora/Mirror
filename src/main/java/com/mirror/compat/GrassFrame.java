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
import java.util.HashMap;
import java.util.Map;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Collects this frame's real views before spending Grassier Grass's single build budget. */
public final class GrassFrame {
    private static final List<Demand> DEMANDS = new ArrayList<>();
    private static final Map<View, Demand> LOD_DEMANDS = new HashMap<>();
    private static final Map<View, Demand> RANGE_DEMANDS = new HashMap<>();
    private static final LongOpenHashSet SECTIONS = new LongOpenHashSet();
    private static long rangeVersion, loadedVersion = -1;
    private static final java.util.function.ToIntFunction<AABB> VISIBILITY = GrassFrame::visibility;
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
        RANGE_DEMANDS.clear(); SECTIONS.clear(); loadedVersion = -1; rangeVersion++;
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
        View view = new View(MirrorPassContext.isActive(), MirrorPassContext.isActive() ? MirrorPassContext.current().viewId() : 0);
        DEMANDS.add(new Demand(view, x, y, z, radius, vertical, eye,
                frustum == null ? null : new Frustum(frustum)));
        // Shader/mesh format comes from the main view, never a transient shadow pipeline.
        if (!MirrorPassContext.isActive()) {
            parameters = new Parameters(tick, iris, compute, blade, snow, eye);
        }
        ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_DRAW);
    }

    public static boolean contains(long key) {
        return SECTIONS.contains(key);
    }

    private static int visibility(AABB bounds) {
        int best = 2;
        for (Demand demand : DEMANDS) if (demand.frustum == null || demand.frustum.isVisible(bounds)) {
            best = Math.min(best, demand.view.mirror ? 1 : 0);
        }
        return best;
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
        GrassCacheAccess.mirror$beginMaintenance();
        lodChanged = DEMANDS.size() != LOD_DEMANDS.size();
        boolean rangeChanged = DEMANDS.size() != RANGE_DEMANDS.size();
        long visibilityVersion = 0;
        for (Demand current : DEMANDS) {
            Demand previous = LOD_DEMANDS.get(current.view);
            lodChanged |= previous == null || !current.sameRange(previous) || current.eye.distanceToSqr(previous.eye) >= 16.0;
            previous = RANGE_DEMANDS.get(current.view);
            rangeChanged |= previous == null || !current.sameRange(previous);
            visibilityVersion += 31L * current.view.hashCode() + 37L * current.eye.hashCode()
                    + GrassCacheAccess.mirror$visibilityFingerprint(current.frustum);
        }
        // Enumerate only the physical client chunk envelope, not distant virtual mirror eyes.
        int loadedRadius = minecraft.options.getEffectiveRenderDistance() + 3;
        int playerX = SectionPos.blockToSectionCoord(minecraft.player.getX());
        int playerZ = SectionPos.blockToSectionCoord(minecraft.player.getZ());
        long chunks = GrassCacheAccess.mirror$loadedChunkVersion();
        if (rangeChanged || loadedVersion != chunks) {
            SECTIONS.clear(); RANGE_DEMANDS.clear();
            for (Demand demand : DEMANDS) {
                RANGE_DEMANDS.put(demand.view, demand);
                for (int sx = Math.max(demand.x - demand.radius, playerX - loadedRadius); sx <= Math.min(demand.x + demand.radius, playerX + loadedRadius); sx++)
                    for (int sz = Math.max(demand.z - demand.radius, playerZ - loadedRadius); sz <= Math.min(demand.z + demand.radius, playerZ + loadedRadius); sz++) {
                        if (!level.getChunkSource().hasChunk(sx, sz)) continue;
                        for (int sy = Math.max(level.getMinSection(), demand.y - demand.vertical); sy <= Math.min(level.getMaxSection() - 1, demand.y + demand.vertical); sy++)
                            if (demand.contains(sx, sy, sz)) SECTIONS.add(SectionPos.asLong(sx, sy, sz));
                    }
            }
            loadedVersion = chunks; rangeVersion++;
        }
        int x = SectionPos.blockToSectionCoord(args.eye.x), y = SectionPos.blockToSectionCoord(args.eye.y);
        int z = SectionPos.blockToSectionCoord(args.eye.z);
        long start = MirrorDiagnostics.startTimer();
        MirrorRenderState saved = MirrorRenderState.capture();
        maintaining = true;
        try {
            GrassCacheAccess.mirror$setDemand(SECTIONS, rangeVersion, visibilityVersion, VISIBILITY);
            GrassCacheAccess.mirror$drainDirty();
            GrassCacheAccess.mirror$evict(x, y, z, 0, 0);
            GrassCacheAccess.mirror$build(level, x, y, z, 0, 0, args.eye,
                    args.tick, args.iris, args.compute, args.blade, args.snow, UNION);
            ThirdPartyFrame.count(ThirdPartyFrame.Work.GRASS_MAINTENANCE);
            if (lodChanged) {
                LOD_DEMANDS.clear();
                for (Demand demand : DEMANDS) LOD_DEMANDS.put(demand.view, demand);
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

    private record View(boolean mirror, long id) { }
    private record Demand(View view, int x, int y, int z, int radius, int vertical, Vec3 eye, Frustum frustum) {
        boolean sameRange(Demand other) {
            return x == other.x && y == other.y && z == other.z && radius == other.radius && vertical == other.vertical;
        }
        boolean contains(int sx, int sy, int sz) {
            long dx = (long) sx - x, dz = (long) sz - z;
            return Math.abs((long) sy - y) <= vertical && dx * dx + dz * dz <= (long) radius * radius;
        }
    }
}
