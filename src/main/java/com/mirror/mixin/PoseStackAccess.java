package com.mirror.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Deque;

/** Stable access to the complete model-view pose stack in both dev and obfuscated clients. */
@Mixin(PoseStack.class)
public interface PoseStackAccess {
    @Accessor("poseStack")
    Deque<PoseStack.Pose> mirror$getPoseStack();
}
