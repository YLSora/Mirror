package com.mirror.mixin;

import com.mirror.client.MirrorLevelRenderer;
import com.mirror.client.MirrorPassContext;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Seeds Embeddium's visibility graph from the physical mirror aperture for reflected cameras.
 *
 * <p>Embeddium normally starts its section traversal from {@link Viewport#getChunkCoord()}, which
 * is correct for the player camera. A recursive mirror camera is virtual, however, and moves farther
 * behind the physical mirrors on every reflection. Once that virtual section leaves Embeddium's
 * loaded section graph, the fallback search can fail to find a section that also passes the narrow
 * off-axis mirror frustum. The terrain list is then empty even though the sky pass still renders.</p>
 *
 * <p>The mirror renderer already computes a physical-world culling origin immediately in front of
 * the active mirror. Use that section only as the traversal seed. The normal reflected-camera
 * distance and frustum tests remain active after traversal reaches the aperture, so this does not
 * turn recursive captures into an all-chunks render.</p>
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller", remap = false)
abstract class EmbeddiumOcclusionCullerMixin {
    /**
     * The first physical seed section can straddle the mirror near plane and fail Embeddium's
     * section-AABB frustum test even though its forward neighbor is visible through the aperture.
     * Keep traversal alive only until the first section that naturally passes the reflected frustum.
     */
    @Unique
    private static boolean mirror$tolerateInitialFrustumFailure;

    @Inject(method = "findVisible", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$beginReflectionTraversal(OcclusionCuller.Visitor visitor, Viewport viewport,
                                                  float searchDistance, boolean useOcclusionCulling,
                                                  int frame, CallbackInfo callback) {
        mirror$tolerateInitialFrustumFailure = mirror$usesPhysicalCullOrigin();
    }

    @Inject(method = "findVisible", at = @At("RETURN"), require = 1, remap = false)
    private void mirror$endReflectionTraversal(OcclusionCuller.Visitor visitor, Viewport viewport,
                                                float searchDistance, boolean useOcclusionCulling,
                                                int frame, CallbackInfo callback) {
        mirror$tolerateInitialFrustumFailure = false;
    }

    @Redirect(method = "init", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;"
                    + "getChunkCoord()Lnet/minecraft/core/SectionPos;"),
            require = 1, remap = false)
    private SectionPos mirror$seedFromPhysicalMirror(Viewport viewport) {
        return mirror$reflectionCullSection(viewport);
    }

    @Redirect(method = "initWithinWorld", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;"
                    + "getChunkCoord()Lnet/minecraft/core/SectionPos;"),
            require = 1, remap = false)
    private SectionPos mirror$seedWithinWorldFromPhysicalMirror(Viewport viewport) {
        return mirror$reflectionCullSection(viewport);
    }

    @Redirect(method = "initOutsideWorldHeight", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;"
                    + "getChunkCoord()Lnet/minecraft/core/SectionPos;"),
            require = 1, remap = false)
    private SectionPos mirror$seedFallbackFromPhysicalMirror(Viewport viewport) {
        return mirror$reflectionCullSection(viewport);
    }

    @Inject(method = "isWithinFrustum", at = @At("RETURN"), cancellable = true,
            require = 1, remap = false)
    private static void mirror$keepInitialTraversalAlive(Viewport viewport, RenderSection section,
                                                          CallbackInfoReturnable<Boolean> callback) {
        if (!mirror$tolerateInitialFrustumFailure || !mirror$usesPhysicalCullOrigin()) return;

        if (Boolean.TRUE.equals(callback.getReturnValue())) {
            // From this point onward the graph is inside the real reflected-camera frustum and
            // Embeddium can resume its normal section-AABB frustum culling.
            mirror$tolerateInitialFrustumFailure = false;
            return;
        }
        callback.setReturnValue(true);
    }

    @Unique
    private static SectionPos mirror$reflectionCullSection(Viewport viewport) {
        if (!mirror$usesPhysicalCullOrigin()) return viewport.getChunkCoord();
        Vec3 origin = MirrorPassContext.current().cullingOrigin();
        if (origin == null) return viewport.getChunkCoord();
        return SectionPos.of(
                ((int) Math.floor(origin.x)) >> 4,
                ((int) Math.floor(origin.y)) >> 4,
                ((int) Math.floor(origin.z)) >> 4);
    }

    @Unique
    private static boolean mirror$usesPhysicalCullOrigin() {
        return MirrorLevelRenderer.isRenderingReflection()
                && MirrorPassContext.isActive()
                && MirrorPassContext.current().cullingOrigin() != null;
    }
}
