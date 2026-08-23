package com.mirror.common.enderman;

import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;

public record EndermanLookResult(Player player, EnderMan enderman) {
}
