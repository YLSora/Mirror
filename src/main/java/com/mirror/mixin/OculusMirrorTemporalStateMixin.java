package com.mirror.mixin;

import com.mirror.client.MirrorDiagnostics;
import com.mirror.client.MirrorPassContext;
import com.mirror.client.MirrorTemporalStateAccess;
import com.mirror.client.OculusRenderTargetsAccess;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Prevents stale persistent history from being consumed when a new reflected camera first enters a
 * shared pipeline, without clearing colortex every time already-known mirror views alternate.
 *
 * <p>The previous implementation cleared on every view-id switch. Two mirrors sharing one slot can
 * switch several times per outer frame, continuously resetting TAA/SSR/shadow history and producing
 * distant-material and entity-shadow flicker. A view now causes one full clear only on its first use
 * (or after the view is explicitly released).</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class OculusMirrorTemporalStateMixin implements MirrorTemporalStateAccess {
    @Shadow
    @Final
    private RenderTargets renderTargets;

    @Unique
    private final Set<Long> mirror$knownViews = new HashSet<>();

    @Inject(method = "beginLevelRendering", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$resetNewViewTemporalAttachments(CallbackInfo callback) {
        if (!MirrorPassContext.isActive()) return;

        long viewId = MirrorPassContext.current().viewId();
        if (!mirror$knownViews.add(viewId)) return;

        // Only the first use of a view invalidates shared persistent history. Normal A/B/A/B view
        // alternation keeps temporal accumulation alive instead of forcing a full clear each pass.
        ((OculusRenderTargetsAccess) (Object) renderTargets).mirror$requestFullClear();
        MirrorDiagnostics.recordTemporalAttachmentReset();
    }

    @Override
    public void mirror$releaseView(long viewId) {
        mirror$knownViews.remove(viewId);
    }
}
