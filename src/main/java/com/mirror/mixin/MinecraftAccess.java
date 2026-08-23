package com.mirror.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccess {
    @Accessor("mainRenderTarget")
    RenderTarget mirror$getMainRenderTarget();

    @Mutable
    @Accessor("mainRenderTarget")
    void mirror$setMainRenderTarget(RenderTarget target);
}
