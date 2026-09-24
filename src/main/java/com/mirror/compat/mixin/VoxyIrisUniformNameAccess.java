package com.mirror.compat.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData$UniformWritingHolder", remap = false)
public interface VoxyIrisUniformNameAccess {
    @Accessor("name") String mirror$uniformName();
}
