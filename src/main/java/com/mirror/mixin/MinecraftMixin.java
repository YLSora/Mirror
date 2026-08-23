package com.mirror.mixin;

import com.mirror.client.MirrorLevelRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "useShaderTransparency", at = @At("RETURN"), cancellable = true)
    private static void mirror$disableFabulousTargets(CallbackInfoReturnable<Boolean> callback) {
        if (MirrorLevelRenderer.isRenderingReflection()) callback.setReturnValue(false);
    }
}
