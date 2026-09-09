package com.mirror.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlStateManager.class)
public interface GlStateManagerAccess {
    @Accessor("TEXTURES")
    static GlStateManager.TextureState[] mirror$getTextures() {
        throw new AssertionError("Mixin accessor was not applied");
    }
}
