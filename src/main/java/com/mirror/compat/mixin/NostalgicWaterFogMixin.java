package com.mirror.compat.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "mod.adrenix.nostalgic.helper.candy.level.fog.WaterFogRenderer", remap = false)
abstract class NostalgicWaterFogMixin {
    @Definition(id = "LivingEntity", type = LivingEntity.class)
    @Expression("(LivingEntity) ?")
    @WrapOperation(method = "setupColor", at = @At("MIXINEXTRAS:EXPRESSION"), require = 1)
    private static LivingEntity mirror$optionalLivingCamera(Object entity, Operation<LivingEntity> original) {
        return entity instanceof LivingEntity ? original.call(entity) : null;
    }

    @WrapOperation(method = "setupColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;getRespiration(" +
                    "Lnet/minecraft/world/entity/LivingEntity;)I", remap = true), require = 1)
    private static int mirror$cameraRespiration(LivingEntity entity, Operation<Integer> original) {
        // Virtual non-living cameras have no equipment; keep Nostalgic's fog calculation intact.
        return entity == null ? 0 : original.call(entity);
    }
}
