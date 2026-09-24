package com.mirror.client;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import me.jellysquid.mods.sodium.client.render.chunk.lists.VisibleChunkCollector;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;

/** Owns mirror-only visibility; terrain maintenance and ship lists remain main-view owned. */
public final class EmbeddiumMirrorViewState {
    private final OcclusionCuller culler;
    private EmbeddiumCameraKey key;
    private SortedRenderLists renderLists = SortedRenderLists.empty();
    private Set<RenderSection> visibleSections = Set.of();
    private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists = Map.of();
    private long cacheHits;
    private long rebuilds;
    private long layerDraws;

    public EmbeddiumMirrorViewState(Long2ReferenceMap<RenderSection> sections, ClientLevel world) {
        culler = new OcclusionCuller(sections, world);
    }

    public void prepare(Viewport viewport, int frame, float distance, boolean occlusion) {
        EmbeddiumCameraKey nextKey = EmbeddiumCameraKey.create(viewport, distance, occlusion);
        if (nextKey != null && nextKey.equals(key)) {
            cacheHits++;
            return;
        }
        VisibleChunkCollector collector = new VisibleChunkCollector(frame);
        EmbeddiumVisibilityContext visibility = EmbeddiumVisibilityContext.begin(frame);
        try (visibility) {
            culler.findVisible(collector, viewport, distance, occlusion, frame);
        }
        renderLists = collector.createRenderLists();
        rebuildLists = collector.getRebuildLists();
        visibleSections = visibility.visibleSections();
        key = nextKey;
        rebuilds++;
    }

    public SortedRenderLists renderLists() { return renderLists; }
    public boolean isVisible(RenderSection section) { return visibleSections.contains(section); }
    public void recordLayerDraw() { layerDraws++; }

    public void contributeRebuilds(Map<ChunkUpdateType, ArrayDeque<RenderSection>> mainQueues) {
        for (var entry : rebuildLists.entrySet()) {
            ChunkUpdateType type = entry.getKey();
            if (type.isSort()) continue;
            ArrayDeque<RenderSection> queue = mainQueues.get(type);
            for (RenderSection section : entry.getValue()) {
                if (queue.size() >= type.getMaximumQueueSize()) break;
                if (!section.isDisposed() && section.getPendingUpdate() == type
                        && section.getBuildCancellationToken() == null && !queue.contains(section)) {
                    queue.add(section);
                }
            }
        }
    }
}
