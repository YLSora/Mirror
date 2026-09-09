package com.mirror.mixin;

import com.mojang.blaze3d.shaders.BlendMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to vanilla's redundant BlendMode cache for render-state transactions. */
@Mixin(BlendMode.class)
public interface BlendModeAccess {
    @Accessor("lastApplied")
    static BlendMode mirror$getLastApplied() {
        throw new AssertionError("Mixin accessor was not applied");
    }

    @Accessor("lastApplied")
    static void mirror$setLastApplied(BlendMode value) {
        throw new AssertionError("Mixin accessor was not applied");
    }
}
