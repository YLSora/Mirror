package com.mirror.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mirror.client.MirrorOuterColors;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    // Only the position-dependent biome fog sample is shared; sunrise tint, fluid fog and rain
    // darkening are still computed for the reflected camera.
    @WrapOperation(method = "setupColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/CubicSampler;gaussianSampleVec3(" +
                    "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/util/CubicSampler$Vec3Fetcher;)" +
                    "Lnet/minecraft/world/phys/Vec3;"), require = 1)
    private static Vec3 mirror$reuseOuterFogBiomeColor(Vec3 samplePosition, CubicSampler.Vec3Fetcher fetcher,
                                                        Operation<Vec3> original) {
        Vec3 shared = MirrorOuterColors.sharedFogBiomeColor();
        if (shared != null) return shared;
        Vec3 color = original.call(samplePosition, fetcher);
        MirrorOuterColors.recordFogBiomeColor(samplePosition, color);
        return color;
    }
}
