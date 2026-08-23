package com.mirror;

import com.mirror.common.MirrorBlock;
import com.mirror.common.MirrorBlockEntity;
import com.mirror.common.CrystallineItem;
import com.mirror.config.MirrorConfig;
import com.mirror.loot.CrystallineLootModifier;
import com.mojang.serialization.Codec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.entity.monster.EnderMan;
import com.mirror.common.enderman.MirrorEndermanObservationGoal;


@Mod(MirrorMod.MOD_ID)
public final class MirrorMod {
    public static final String MOD_ID = "mirror";
    public static final String DISPLAY_NAME = "Mirror";

    public static final TagKey<EntityType<?>> CANT_SEE_THROUGH_MIRROR = TagKey.create(
            Registries.ENTITY_TYPE,
            new ResourceLocation(MOD_ID, "cant_see_through_mirror"));

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID);
    public static final DeferredRegister<Codec<? extends net.minecraftforge.common.loot.IGlobalLootModifier>> LOOT_MODIFIERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MOD_ID);

    public static final RegistryObject<MirrorBlock> MIRROR = BLOCKS.register("mirror", () ->
            new MirrorBlock(Block.Properties.of()
                    .sound(SoundType.GLASS)
                    .mapColor(MapColor.METAL)
                    .strength(0.3f)
                    .noOcclusion()));

    public static final RegistryObject<Item> MIRROR_ITEM = ITEMS.register("mirror", () ->
            new BlockItem(MIRROR.get(), new Item.Properties().rarity(Rarity.RARE)));

    public static final RegistryObject<Item> CRYSTALLINE = ITEMS.register("crystalline", () ->
            new CrystallineItem(new Item.Properties().rarity(Rarity.RARE)));

    public static final RegistryObject<BlockEntityType<MirrorBlockEntity>> MIRROR_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("mirror", () -> BlockEntityType.Builder.of(
                    MirrorBlockEntity::new, MIRROR.get()).build(null));

    public static final RegistryObject<Codec<? extends net.minecraftforge.common.loot.IGlobalLootModifier>>
            CRYSTALLINE_LOOT_MODIFIER = LOOT_MODIFIERS.register("crystalline", () -> CrystallineLootModifier.CODEC);

    public MirrorMod() {
        IEventBus modBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        LOOT_MODIFIERS.register(modBus);

        MirrorConfig.register();
        modBus.addListener(this::addCreativeTabContents);
        MinecraftForge.EVENT_BUS.addListener(MirrorMod::onEntityJoinLevel);
    }

    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()
                && event.getEntity() instanceof EnderMan enderman
                && MirrorConfig.COMMON.enableEndermanObservation.get()) {
            MirrorEndermanObservationGoal.install(enderman);
        }
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(MIRROR_ITEM);
        } else if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.INGREDIENTS) {
            event.accept(CRYSTALLINE);
        }
    }

    public static String id(String path) {
        return MOD_ID + ":" + path;
    }
}
