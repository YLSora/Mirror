package com.mirror.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mirror.client.EmbeddiumViewStateAccess;
import com.mirror.client.EmbeddiumSectionStateAccess;
import com.mirror.client.EmbeddiumViewSetupState;
import com.mirror.client.MirrorLevelRenderer;
import com.mirror.client.MirrorPassContext;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.extensions.IForgeBlockEntity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/** Binds mirror-only visibility for the full capture without repeating main-view preparation. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
abstract class EmbeddiumWorldRendererMixin implements EmbeddiumViewStateAccess {
    @Shadow private Viewport currentViewport;
    @Shadow private RenderSectionManager renderSectionManager;

    @Unique private final Deque<EmbeddiumViewSetupState> mirror$setups = new ArrayDeque<>();

    @Inject(method = "setupTerrain(Lnet/minecraft/client/Camera;Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;IZZ)V",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void mirror$beginSetup(Camera camera, Viewport viewport, int frame, boolean spectator,
                                   boolean updateImmediately, CallbackInfo callback) {
        if (!MirrorPassContext.isActive()) return;
        mirror$setups.push(new EmbeddiumViewSetupState(MirrorPassContext.current().viewId(),
                currentViewport, renderSectionManager));
        currentViewport = viewport;
        ((EmbeddiumSectionStateAccess) renderSectionManager).mirror$beginView(camera, viewport, frame, spectator);
        callback.cancel();
    }

    @Override
    public void mirror$finishCapture(long viewId) {
        while (!mirror$setups.isEmpty() && mirror$setups.peek().viewId() == viewId) {
            EmbeddiumViewSetupState state = mirror$setups.pop();
            try {
                ((EmbeddiumSectionStateAccess) state.manager()).mirror$endView();
            } finally {
                currentViewport = state.outerViewport();
            }
        }
    }

    @Override
    public void mirror$releaseView(long viewId) {
        if (renderSectionManager instanceof EmbeddiumSectionStateAccess access) {
            access.mirror$releaseView(viewId);
        }
    }
    // Embeddium turns block-entity frustum culling off whenever Valkyrien Skies is installed, because
    // VS re-enters these loops with shipyard-space render lists. A mirror pass then submitted every
    // block entity of every section touched by its narrow aperture, once per view. Mirror passes cull
    // against the reflected viewport; shipyard block entities keep the unculled path (see below).
    @ModifyExpressionValue(method = "renderBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/RenderBuffers;"
            + "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;F"
            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD"
            + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V",
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target =
                    "Lme/jellysquid/mods/sodium/client/render/SodiumWorldRenderer;ENABLE_BLOCKENTITY_CULLING:Z"),
            require = 1, remap = false)
    private boolean mirror$cullMirrorPassBlockEntities(boolean enabled) {
        return enabled || MirrorLevelRenderer.isRenderingReflection();
    }

    @ModifyExpressionValue(method = "renderGlobalBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/RenderBuffers;"
            + "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;F"
            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD"
            + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V",
            at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target =
                    "Lme/jellysquid/mods/sodium/client/render/SodiumWorldRenderer;ENABLE_BLOCKENTITY_CULLING:Z"),
            require = 1, remap = false)
    private boolean mirror$cullMirrorPassGlobalBlockEntities(boolean enabled) {
        return enabled || MirrorLevelRenderer.isRenderingReflection();
    }

    // Viewport#isBoxVisible accepts INFINITE_EXTENT_AABB unconditionally. Only nested mirrors and
    // shipyard block entities take that path; all others are culled by the reflected viewport.
    @WrapOperation(method = "renderBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/RenderBuffers;"
            + "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;F"
            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD"
            + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/BlockEntity;"
                            + "getRenderBoundingBox()Lnet/minecraft/world/phys/AABB;"),
            require = 1, remap = false)
    private AABB mirror$reflectionCullingBounds(BlockEntity blockEntity, Operation<AABB> original) {
        return MirrorLevelRenderer.bypassesBlockEntityCulling(blockEntity)
                ? IForgeBlockEntity.INFINITE_EXTENT_AABB : original.call(blockEntity);
    }

    @WrapOperation(method = "renderGlobalBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/RenderBuffers;"
            + "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;F"
            + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD"
            + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/BlockEntity;"
                            + "getRenderBoundingBox()Lnet/minecraft/world/phys/AABB;"),
            require = 1, remap = false)
    private AABB mirror$reflectionGlobalCullingBounds(BlockEntity blockEntity, Operation<AABB> original) {
        return MirrorLevelRenderer.bypassesBlockEntityCulling(blockEntity)
                ? IForgeBlockEntity.INFINITE_EXTENT_AABB : original.call(blockEntity);
    }

}
