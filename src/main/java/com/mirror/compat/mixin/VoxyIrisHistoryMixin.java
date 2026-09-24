package com.mirror.compat.mixin;

import com.mirror.client.MirrorPassContext;
import com.mirror.compat.ThirdPartyFrame;
import com.mirror.compat.VoxyIrisHistoryAccess;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Viewport.class, remap = false)
abstract class VoxyIrisHistoryMixin implements VoxyIrisHistoryAccess {
    @Unique private Matrix4f mirror$currentProjection;
    @Unique private Matrix4f mirror$currentModelView;
    @Unique private Matrix4f mirror$previousProjection;
    @Unique private Matrix4f mirror$previousModelView;
    @Unique private long mirror$historyFrame = Long.MIN_VALUE;
    @Unique private int mirror$historyWidth;
    @Unique private int mirror$historyHeight;
    @Unique private boolean mirror$continuous;

    @Inject(method = "update", at = @At("RETURN"), require = 1)
    private void mirror$captureMatrices(CallbackInfoReturnable<Viewport<?>> callback) {
        if (!MirrorPassContext.isActive() || ThirdPartyFrame.auxiliary()) return;
        Viewport<?> view = (Viewport<?>) (Object) this;
        long frame = ThirdPartyFrame.id();
        if (mirror$currentProjection == null) {
            mirror$currentProjection = new Matrix4f();
            mirror$currentModelView = new Matrix4f();
            mirror$previousProjection = new Matrix4f();
            mirror$previousModelView = new Matrix4f();
        }
        if (mirror$historyFrame != frame) {
            mirror$continuous = mirror$historyFrame == frame - 1;
            mirror$previousProjection.set(mirror$currentProjection);
            mirror$previousModelView.set(mirror$currentModelView);
        }
        if (mirror$historyWidth != view.width || mirror$historyHeight != view.height) {
            mirror$continuous = false;
        }
        // Voxy may set up the same viewport more than once per pass. Only the outer frame
        // advances history; keep the actual Voxy depth projection, including reverse-Z.
        mirror$currentProjection.set(view.projection);
        mirror$currentModelView.set(view.modelView);
        if (!mirror$continuous) {
            mirror$previousProjection.set(view.projection);
            mirror$previousModelView.set(view.modelView);
        }
        mirror$historyFrame = frame;
        mirror$historyWidth = view.width;
        mirror$historyHeight = view.height;
    }

    @Override public Matrix4f mirror$previousProjection() {
        return new Matrix4f(mirror$previousProjection == null
                ? ((Viewport<?>) (Object) this).projection : mirror$previousProjection);
    }

    @Override public Matrix4f mirror$previousModelView() {
        return new Matrix4f(mirror$previousModelView == null
                ? ((Viewport<?>) (Object) this).modelView : mirror$previousModelView);
    }

    @Override public Matrix4f mirror$previousViewProjection() {
        return mirror$previousProjection().mul(mirror$previousModelView());
    }
}
