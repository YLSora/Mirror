package com.mirror.compat.mixin;

import com.mirror.client.MirrorLevelRenderer;
import fi.dy.masa.litematica.data.DataManager;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.render.LitematicaRenderer", remap = false)
abstract class ForgematicaRendererMixin {
    @Shadow private boolean renderPiecewiseSchematic;
    @Shadow private boolean renderPiecewiseBlocks;

    // Without placements the schematic world is empty, yet every terrain layer still sets up its
    // shader state and builds a profiler label. Clearing the flags makes all per-layer hooks of a
    // reflection pass return immediately; the player's view keeps Forgematica's own flow.
    @Inject(method = "piecewisePrepareAndUpdate", at = @At("HEAD"), cancellable = true, require = 1)
    private void mirror$skipEmptySchematicInReflection(Frustum frustum, CallbackInfo ci) {
        if (!MirrorLevelRenderer.isRenderingReflection()
                || !DataManager.getSchematicPlacementManager().getAllSchematicsPlacements().isEmpty()) return;
        renderPiecewiseSchematic = false;
        renderPiecewiseBlocks = false;
        ci.cancel();
    }
}
