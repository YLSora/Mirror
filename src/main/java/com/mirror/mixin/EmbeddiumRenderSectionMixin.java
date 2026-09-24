package com.mirror.mixin;

import com.mirror.client.EmbeddiumVisibilityContext;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records per-section occlusion marks before a mirror traversal mutates them. */
@Pseudo
@Mixin(value = RenderSection.class, remap = false)
abstract class EmbeddiumRenderSectionMixin {
    @Inject(method = "setLastVisibleFrame(I)V", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$captureVisibleFrame(int frame, CallbackInfo callback) {
        EmbeddiumVisibilityContext.capture((RenderSection) (Object) this);
    }

    @Inject(method = "setIncomingDirections(I)V", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$captureIncomingDirections(int directions, CallbackInfo callback) {
        EmbeddiumVisibilityContext.capture((RenderSection) (Object) this);
    }

    @Inject(method = "addIncomingDirections(I)V", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$captureAddedDirections(int directions, CallbackInfo callback) {
        EmbeddiumVisibilityContext.capture((RenderSection) (Object) this);
    }
}
