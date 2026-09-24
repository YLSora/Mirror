package com.mirror.mixin;

import com.mirror.client.EmbeddiumMirrorViewState;
import com.mirror.client.EmbeddiumSectionStateAccess;
import com.mirror.client.EmbeddiumViewEpoch;
import com.mirror.client.MirrorLevelRenderer;
import com.mirror.client.MirrorPassContext;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Mirror visibility never mutates main-owned region lists or runs terrain/ship maintenance. */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
abstract class EmbeddiumRenderSectionManagerMixin implements EmbeddiumSectionStateAccess {
    @Shadow private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists;
    @Shadow @Final private Long2ReferenceMap<RenderSection> sectionByPosition;
    @Shadow @Final private ClientLevel world;
    @Shadow private SortedRenderLists renderLists;
    @Shadow private float getSearchDistance() { throw new AssertionError(); }
    @Shadow private boolean shouldUseOcclusionCulling(Camera camera, boolean spectator) { throw new AssertionError(); }

    @Unique private final Map<Long, EmbeddiumMirrorViewState> mirror$views = new HashMap<>();
    @Unique private final Deque<SortedRenderLists> mirror$outerLists = new ArrayDeque<>();
    @Unique private final Deque<EmbeddiumMirrorViewState> mirror$outerViews = new java.util.LinkedList<>();
    @Unique private EmbeddiumMirrorViewState mirror$activeView;

    @Override
    public void mirror$beginView(Camera camera, Viewport viewport, int frame, boolean spectator) {
        mirror$outerLists.push(renderLists);
        mirror$outerViews.push(mirror$activeView);
        EmbeddiumMirrorViewState view = mirror$views.computeIfAbsent(MirrorPassContext.current().viewId(),
                ignored -> new EmbeddiumMirrorViewState(sectionByPosition, world));
        mirror$activeView = view;
        view.prepare(viewport, frame, getSearchDistance(), shouldUseOcclusionCulling(camera, spectator));
        view.contributeRebuilds(rebuildLists);
        renderLists = view.renderLists();
    }

    @Override
    public void mirror$endView() {
        renderLists = mirror$outerLists.pop();
        mirror$activeView = mirror$outerViews.pop();
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), require = 1)
    private void mirror$countLayerDraw(CallbackInfo callback) {
        if (mirror$activeView != null) mirror$activeView.recordLayerDraw();
    }

    @Inject(method = "isSectionVisible(III)Z", at = @At("HEAD"), cancellable = true, require = 1)
    private void mirror$useViewVisibility(int x, int y, int z, CallbackInfoReturnable<Boolean> callback) {
        if (mirror$activeView != null) {
            RenderSection section = sectionByPosition.get(SectionPos.asLong(x, y, z));
            callback.setReturnValue(section != null && mirror$activeView.isVisible(section));
        }
    }

    @Inject(method = "getSearchDistance()F", at = @At("RETURN"), cancellable = true, require = 1)
    private void mirror$capDeepReflectionSearchDistance(CallbackInfoReturnable<Float> callback) {
        if (MirrorPassContext.isActive() && MirrorLevelRenderer.isRecursivePass()) {
            callback.setReturnValue(MirrorPassContext.current().renderDistance());
        }
    }

    @Inject(method = {"onSectionAdded(III)V", "onSectionRemoved(III)V", "uploadChunks()V", "scheduleRebuild(IIIZ)V"},
            at = @At(value = "FIELD", target =
                    "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSectionManager;needsUpdate:Z",
                    opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER), require = 4)
    private void mirror$invalidateGeometry(CallbackInfo callback) {
        EmbeddiumViewEpoch.invalidate();
    }

    @Inject(method = "markGraphDirty()V", at = @At("HEAD"), cancellable = true, require = 1)
    private void mirror$invalidateExplicitGraphChange(CallbackInfo callback) {
        if (MirrorPassContext.isActive()) callback.cancel();
        else EmbeddiumViewEpoch.invalidate();
    }

    @Inject(method = "destroy()V", at = @At("HEAD"), require = 1)
    private void mirror$clearViewListsOnDestroy(CallbackInfo callback) {
        mirror$views.clear();
        EmbeddiumViewEpoch.invalidate();
    }

    @Override public void mirror$releaseView(long viewId) { mirror$views.remove(viewId); }
}
