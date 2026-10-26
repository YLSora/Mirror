package com.mirror.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mirror.client.MirrorOuterColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    // Wrap the whole method so mod hooks inside it still run for the player's view; reflections
    // receive that already-modified result.
    @WrapMethod(method = "getSkyColor")
    private Vec3 mirror$reuseOuterSkyColor(Vec3 position, float partialTick, Operation<Vec3> original) {
        ClientLevel level = (ClientLevel) (Object) this;
        Vec3 shared = MirrorOuterColors.sharedSkyColor(level, partialTick);
        if (shared != null) return shared;
        Vec3 color = original.call(position, partialTick);
        MirrorOuterColors.recordSkyColor(level, position, partialTick, color);
        return color;
    }
}
