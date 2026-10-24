package com.mirror.compat.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "com.leonardoinc22.shortgrass.client.render.GrassSectionCache", remap = false)
public interface GrassCacheAccess {
    @Invoker("beginMaintenance")
    static void mirror$beginMaintenance() { throw new AssertionError(); }

    @Invoker("loadedChunkVersion")
    static long mirror$loadedChunkVersion() { throw new AssertionError(); }

    @Invoker("visibilityFingerprint")
    static int mirror$visibilityFingerprint(Frustum frustum) { throw new AssertionError(); }

    @Invoker("setViewDemand")
    static void mirror$setDemand(it.unimi.dsi.fastutil.longs.LongSet sections, long version, long visibilityVersion,
                                 java.util.function.ToIntFunction<net.minecraft.world.phys.AABB> visibility) { throw new AssertionError(); }
    @Invoker("drainPendingDirtySections")
    static void mirror$drainDirty() { throw new AssertionError(); }

    @Invoker("evictOutOfRange")
    static void mirror$evict(int x, int y, int z, int radius, int vertical) { throw new AssertionError(); }

    @Invoker("buildBudgeted")
    static void mirror$build(ClientLevel level, int x, int y, int z, int radius, int vertical,
                             Vec3 eye, long tick, boolean iris, boolean compute,
                             TextureAtlasSprite blade, TextureAtlasSprite snow, Frustum frustum) {
        throw new AssertionError();
    }
}
