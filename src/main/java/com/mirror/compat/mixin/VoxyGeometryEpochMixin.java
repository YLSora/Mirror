package com.mirror.compat.mixin;

import com.mirror.compat.ThirdPartyFrame;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AsyncNodeManager.class, remap = false)
abstract class VoxyGeometryEpochMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL43C;glDispatchCompute(III)V"), require = 2)
    private void mirror$invalidateOldGeometryReferences(CallbackInfo ci) {
        ThirdPartyFrame.voxyGeometryChanged();
    }
}
