package com.mirror.mixin;

import com.mirror.client.EmbeddiumViewportState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.viewport.Viewport", remap = false)
abstract class EmbeddiumViewportMixin implements EmbeddiumViewportState {
    @Unique private Matrix4f mirror$cullMatrix;

    @Override public Matrix4f mirror$getCullMatrix() { return mirror$cullMatrix; }
    @Override public void mirror$setCullMatrix(Matrix4f matrix) {
        mirror$cullMatrix = new Matrix4f(matrix);
    }
}
