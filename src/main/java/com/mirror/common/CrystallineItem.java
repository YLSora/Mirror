package com.mirror.common;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class CrystallineItem extends Item {
    public CrystallineItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
