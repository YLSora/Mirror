package com.mirror.client;

import com.mirror.MirrorMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = MirrorMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MirrorClient {
    public static ShaderInstance MIRROR_SHADER;
    private MirrorClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(MirrorMod.MIRROR_BLOCK_ENTITY.get(), MirrorBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        ShaderInstance shader = new ShaderInstance(event.getResourceProvider(),
                new ResourceLocation(MirrorMod.MOD_ID, "mirror_material"), DefaultVertexFormat.NEW_ENTITY);
        event.registerShader(shader, registered -> MIRROR_SHADER = registered);
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((barrier, resources, preparationProfiler, executionProfiler,
                                       preparationExecutor, executionExecutor) ->
                barrier.wait(null).thenRun(MirrorTextureManager::clear));
    }

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(MirrorClient::renderTick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(MirrorClient::levelUnload);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(MirrorClient::loggingOut);
    }

    private static void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) MirrorTextureManager.processPending();
    }

    private static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) MirrorTextureManager.clear();
    }

    private static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MirrorTextureManager.clear();
    }
}
