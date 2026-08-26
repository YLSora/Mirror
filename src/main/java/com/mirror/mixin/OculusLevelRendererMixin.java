package com.mirror.mixin;

import com.mirror.client.OculusCompat;
import com.mirror.client.OculusLevelRendererAccess;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Oculus 1.8.0 LevelRenderer hooks for the mirror transaction. */
@Pseudo
@Mixin(LevelRenderer.class)
abstract class OculusLevelRendererMixin implements OculusLevelRendererAccess {
    @Override
    public Object mirror$getPipeline() {
        return OculusCompat.getLevelRendererPipeline((LevelRenderer) (Object) this);
    }

    @Override
    public void mirror$setPipeline(Object value) {
        OculusCompat.setLevelRendererPipeline((LevelRenderer) (Object) this, value);
    }
}
