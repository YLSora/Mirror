package com.mirror.loot;

import com.mirror.MirrorMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

public final class CrystallineLootModifier extends LootModifier {
    private static final ResourceLocation ELDER_GUARDIAN =
            new ResourceLocation("minecraft", "entities/elder_guardian");

    public CrystallineLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (ELDER_GUARDIAN.equals(context.getQueriedLootTableId())) {
            generatedLoot.add(new ItemStack(MirrorMod.CRYSTALLINE.get()));
        }
        return generatedLoot;
    }

    public static final Codec<CrystallineLootModifier> CODEC = RecordCodecBuilder.create(instance ->
            codecStart(instance).apply(instance, CrystallineLootModifier::new));

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
