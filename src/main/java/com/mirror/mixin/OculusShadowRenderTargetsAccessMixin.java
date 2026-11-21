package com.mirror.mixin;

import com.mirror.client.MirrorShadowRenderTargetsAccess;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderTargets", remap = false)
public interface OculusShadowRenderTargetsAccessMixin extends MirrorShadowRenderTargetsAccess {
    @Override
    @Accessor("flipped")
    boolean[] mirror$getFlipped();

    @Override
    @Accessor("ownedFramebuffers")
    List<GlFramebuffer> mirror$getOwnedFramebuffers();
}
