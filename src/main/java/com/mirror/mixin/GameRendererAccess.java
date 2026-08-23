package com.mirror.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccess {
    @Accessor("mainCamera")
    Camera mirror$getMainCamera();

    @Mutable
    @Accessor("mainCamera")
    void mirror$setMainCamera(Camera camera);

    @Accessor("renderDistance")
    float mirror$getRenderDistance();

    @Accessor("renderDistance")
    void mirror$setRenderDistance(float value);
}
