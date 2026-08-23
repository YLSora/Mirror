package com.mirror.common.enderman;

import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;

/** 1.20.1 equivalent of EnderMan's private isLookingAtMe check. */
final class EndermanSight {
    private EndermanSight() {
    }

    static boolean isLookingAtMe(EnderMan enderman, Player player) {
        ItemStack helmet = player.getInventory().armor.get(3);
        if (ForgeHooks.shouldSuppressEnderManAnger(enderman, player, helmet)) return false;

        Vec3 view = player.getViewVector(1.0F).normalize();
        Vec3 toEnderman = new Vec3(
                enderman.getX() - player.getX(),
                enderman.getEyeY() - player.getEyeY(),
                enderman.getZ() - player.getZ());
        double distance = toEnderman.length();
        if (distance <= 1e-6) return false;

        double alignment = view.dot(toEnderman.normalize());
        return alignment > 1.0 - 0.025 / distance && player.hasLineOfSight(enderman);
    }
}
