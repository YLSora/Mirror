package com.mirror.mixin;

import com.mirror.client.MirrorShadowRendererAccess;
import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public interface OculusShadowRendererAccessMixin extends MirrorShadowRendererAccess {
    @Override
    @Accessor("buffers")
    RenderBuffers mirror$getBuffers();
}
