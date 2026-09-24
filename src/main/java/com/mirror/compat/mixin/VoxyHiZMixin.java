package com.mirror.compat.mixin;

import com.mirror.compat.ThirdPartyFrame;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HiZBuffer.class, remap = false)
abstract class VoxyHiZMixin {
    @Inject(method = "alloc", at = @At("RETURN"), require = 1)
    private void mirror$countAllocation(CallbackInfo ci) {
        ThirdPartyFrame.count(ThirdPartyFrame.Work.VOXY_HIZ_ALLOC);
    }
}
