package com.mirror.mixin;

import com.mirror.client.MirrorPassContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies the previous camera position belonging to the same persistent mirror view. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms$CameraPositionTracker", remap = false)
abstract class OculusCameraPositionTrackerMixin {
    @Shadow
    private Vector3d currentCameraPosition;

    @Shadow
    private Vector3d currentCameraPositionUnshifted;

    @Inject(method = "getPreviousCameraPosition", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$useViewPreviousShiftedCamera(CallbackInfoReturnable<Vector3d> callback) {
        if (!MirrorPassContext.isActive()) return;
        Vec3 previous = MirrorPassContext.current().previousCameraPosition();
        // Iris may shift large world coordinates to preserve float precision. Apply the tracker's
        // current shift to this view's unshifted prior camera so both camera uniforms share the
        // same coordinate domain.
        double shiftX = currentCameraPosition.x - currentCameraPositionUnshifted.x;
        double shiftY = currentCameraPosition.y - currentCameraPositionUnshifted.y;
        double shiftZ = currentCameraPosition.z - currentCameraPositionUnshifted.z;
        callback.setReturnValue(new Vector3d(
                previous.x + shiftX, previous.y + shiftY, previous.z + shiftZ));
    }

    @Inject(method = "getPreviousCameraPositionUnshifted", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$useViewPreviousUnshiftedCamera(CallbackInfoReturnable<Vector3d> callback) {
        if (!MirrorPassContext.isActive()) return;
        Vec3 previous = MirrorPassContext.current().previousCameraPosition();
        callback.setReturnValue(new Vector3d(previous.x, previous.y, previous.z));
    }
}
