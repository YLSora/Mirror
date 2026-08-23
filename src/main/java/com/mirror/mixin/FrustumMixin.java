package com.mirror.mixin;

import com.mirror.client.EmbeddiumCompat;
import com.mirror.client.MirrorLevelRenderer;
import com.mirror.client.MirrorLevelRendererHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflection-only replacement for LevelRenderer's camera-cube frustum pass.  The reflected
 * projection is asymmetric, so the vanilla offset loop is not a valid visibility test.  The
 * normal renderer, and Embeddium's renderer, continue to execute their own culling paths.
 */
@Mixin(LevelRenderer.class)
abstract class FrustumMixin {
    @Inject(method = "setupRender", at = @At("HEAD"), cancellable = true)
    private void mirror$setupReflectionFrustum(Camera camera, Frustum frustum,
                                                boolean hasCapturedFrustum, boolean isSpectator,
                                                CallbackInfo callback) {
        if (!MirrorLevelRenderer.isRenderingReflection() || EmbeddiumCompat.ownsSectionCulling()) return;
        MirrorLevelRendererHooks.applyFrustum((LevelRenderer) (Object) this, frustum);
        callback.cancel();
    }
}
