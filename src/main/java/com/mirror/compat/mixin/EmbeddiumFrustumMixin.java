package com.mirror.compat.mixin;

import com.mirror.client.EmbeddiumViewportState;
import com.mirror.client.MirrorPassContext;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Runs after Embeddium adds its viewport provider to the vanilla frustum. */
@Mixin(value = Frustum.class, priority = 900)
abstract class EmbeddiumFrustumMixin {
    @Shadow @Final private Matrix4f matrix;

    @ModifyReturnValue(method = "sodium$createViewport", at = @At("RETURN"), remap = false, require = 1)
    private Viewport mirror$describeFrustum(Viewport viewport) {
        if (MirrorPassContext.isActive() && ((Object) this).getClass() == Frustum.class) {
            ((EmbeddiumViewportState) (Object) viewport).mirror$setCullMatrix(matrix);
        }
        return viewport;
    }
}
