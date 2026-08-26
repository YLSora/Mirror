package com.mirror.bridge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Access to vanilla LevelRenderer state for a temporary reflection culling pass. */
@Mixin(LevelRenderer.class)
public interface LevelRendererBridge {
    @Accessor("viewArea") ViewArea mirror$getViewArea();
    @Accessor("renderChunkStorage") @SuppressWarnings("rawtypes") AtomicReference mirror$getRenderChunkStorage();
    @Accessor("renderChunksInFrustum") @SuppressWarnings("rawtypes") ObjectArrayList mirror$getRenderChunksInFrustum();
    @Accessor("needsFrustumUpdate") AtomicBoolean mirror$getNeedsFrustumUpdate();
    @Accessor("needsFullRenderChunkUpdate") boolean mirror$getNeedsFullRenderChunkUpdate();
    @Accessor("needsFullRenderChunkUpdate") void mirror$setNeedsFullRenderChunkUpdate(boolean value);
    @Accessor("lastFullRenderChunkUpdate") Future<?> mirror$getLastFullRenderChunkUpdate();
    @Accessor("lastFullRenderChunkUpdate") void mirror$setLastFullRenderChunkUpdate(Future<?> value);
    @Accessor("nextFullUpdateMillis") AtomicLong mirror$getNextFullUpdateMillis();
    @Accessor("lastViewDistance") int mirror$getLastViewDistance();
    @Accessor("lastViewDistance") void mirror$setLastViewDistance(int value);
    @Accessor("lastCameraX") double mirror$getLastCameraX();
    @Accessor("lastCameraX") void mirror$setLastCameraX(double value);
    @Accessor("lastCameraY") double mirror$getLastCameraY();
    @Accessor("lastCameraY") void mirror$setLastCameraY(double value);
    @Accessor("lastCameraZ") double mirror$getLastCameraZ();
    @Accessor("lastCameraZ") void mirror$setLastCameraZ(double value);
    @Accessor("lastCameraChunkX") int mirror$getLastCameraChunkX();
    @Accessor("lastCameraChunkX") void mirror$setLastCameraChunkX(int value);
    @Accessor("lastCameraChunkY") int mirror$getLastCameraChunkY();
    @Accessor("lastCameraChunkY") void mirror$setLastCameraChunkY(int value);
    @Accessor("lastCameraChunkZ") int mirror$getLastCameraChunkZ();
    @Accessor("lastCameraChunkZ") void mirror$setLastCameraChunkZ(int value);
    @Accessor("prevCamX") double mirror$getPrevCamX();
    @Accessor("prevCamX") void mirror$setPrevCamX(double value);
    @Accessor("prevCamY") double mirror$getPrevCamY();
    @Accessor("prevCamY") void mirror$setPrevCamY(double value);
    @Accessor("prevCamZ") double mirror$getPrevCamZ();
    @Accessor("prevCamZ") void mirror$setPrevCamZ(double value);
    @Accessor("prevCamRotX") double mirror$getPrevCamRotX();
    @Accessor("prevCamRotX") void mirror$setPrevCamRotX(double value);
    @Accessor("prevCamRotY") double mirror$getPrevCamRotY();
    @Accessor("prevCamRotY") void mirror$setPrevCamRotY(double value);
}
