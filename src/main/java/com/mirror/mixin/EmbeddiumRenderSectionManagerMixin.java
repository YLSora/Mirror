package com.mirror.mixin;

import com.mirror.client.MirrorLevelRenderer;
import com.mirror.client.MirrorPassContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Map;

/**
 * Keeps Embeddium terrain maintenance out of second-and-deeper mirror passes.
 *
 * <p>Embeddium owns one global RenderSectionManager. Feeding every reflected camera through the
 * normal setup path otherwise makes deep mirrors create INITIAL_BUILD/REBUILD/SORT work for their
 * own distant visibility graph and upload those results while another camera is being rendered.
 * Deep reflections only need a read-only view of meshes already maintained by the main/direct
 * passes, so they still rebuild the visibility list but never enqueue deep-camera build/sort work
 * or upload terrain during a recursive capture.</p>
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
abstract class EmbeddiumRenderSectionManagerMixin {
    @Shadow
    private Map<?, ?> rebuildLists;

    /**
     * Embeddium normally searches its configured main-world distance and does not observe
     * GameRenderer.renderDistance. Apply Mirror's recursive distance budget to the actual
     * Embeddium visibility graph as well.
     */
    @Inject(method = "getSearchDistance()F", at = @At("RETURN"), cancellable = true,
            require = 1, remap = false)
    private void mirror$capDeepReflectionSearchDistance(CallbackInfoReturnable<Float> callback) {
        if (!mirror$isDeepReflection()) return;

        float mirrorDistance = MirrorPassContext.current().renderDistance();
        float embeddiumDistance = callback.getReturnValueF();
        if (Float.isFinite(mirrorDistance) && mirrorDistance > 0.0f
                && mirrorDistance < embeddiumDistance) {
            callback.setReturnValue(mirrorDistance);
        }
    }

    /** Completed global build results are uploaded once by the main/direct terrain pass. */
    @Inject(method = "uploadChunks()V", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$skipDeepReflectionUploads(CallbackInfo callback) {
        if (mirror$isDeepReflection()) callback.cancel();
    }

    /**
     * Dynamic translucent sorting stores the camera position back into each RenderSection. A
     * reflected camera would therefore make the main camera and every recursion depth re-sort the
     * same distant translucent sections against one another every frame.
     */
    @Inject(method = "checkTranslucencyChange()V", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$skipDeepReflectionTranslucencyResort(CallbackInfo callback) {
        if (mirror$isDeepReflection()) callback.cancel();
    }

    /**
     * VisibleChunkCollector also returns INITIAL_BUILD/REBUILD queues for unbuilt sections. Keep
     * its render list (already-built geometry) but discard all mutation queues before the next
     * camera can consume them. The section's pending-update flag itself is untouched, so the
     * main/direct pass can enqueue the work normally when that section is relevant there.
     */
    @Inject(method = "createTerrainRenderList(Lnet/minecraft/client/Camera;Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;IZ)V", at = @At("RETURN"),
            require = 1, remap = false)
    private void mirror$discardDeepReflectionTerrainQueues(CallbackInfo callback) {
        if (!mirror$isDeepReflection() || rebuildLists == null) return;

        for (Object value : rebuildLists.values()) {
            if (value instanceof Collection<?> collection) {
                collection.clear();
            }
        }
    }

    private static boolean mirror$isDeepReflection() {
        return MirrorLevelRenderer.isRecursivePass() && MirrorPassContext.isActive();
    }
}
