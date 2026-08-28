package com.mirror.mixin;

import com.mirror.client.OculusRenderTargetsAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** Narrow access to Iris' full-clear flag; no render-target allocation logic is replaced. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.targets.RenderTargets", remap = false)
abstract class OculusRenderTargetsMixin implements OculusRenderTargetsAccess {
    @Shadow
    private boolean fullClearRequired;

    @Override
    public void mirror$requestFullClear() {
        fullClearRequired = true;
    }
}
