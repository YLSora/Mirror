package com.mirror.client;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Captures the mutable occlusion marks touched by one read-only mirror traversal. */
public final class EmbeddiumVisibilityContext implements AutoCloseable {
    private static final ThreadLocal<EmbeddiumVisibilityContext> ACTIVE = new ThreadLocal<>();

    private final Map<RenderSection, Snapshot> snapshots = new IdentityHashMap<>();
    private final Map<RenderRegion, ChunkRenderList> lists = new IdentityHashMap<>();
    private final Set<RenderSection> visibleSections = Collections.newSetFromMap(new IdentityHashMap<>());
    private final int frame;
    private boolean closed;

    private EmbeddiumVisibilityContext(int frame) {
        this.frame = frame;
    }

    public static EmbeddiumVisibilityContext begin(int frame) {
        if (ACTIVE.get() != null) throw new IllegalStateException("nested Embeddium visibility context");
        EmbeddiumVisibilityContext context = new EmbeddiumVisibilityContext(frame);
        ACTIVE.set(context);
        return context;
    }

    public static void capture(RenderSection section) {
        EmbeddiumVisibilityContext context = ACTIVE.get();
        if (context == null || context.snapshots.containsKey(section)) return;
        context.snapshots.put(section, new Snapshot(
                section.getLastVisibleFrame(), section.getIncomingDirections()));
    }

    public static ChunkRenderList renderList(RenderRegion region) {
        EmbeddiumVisibilityContext context = ACTIVE.get();
        return context == null ? region.getRenderList()
                : context.lists.computeIfAbsent(region, ChunkRenderList::new);
    }

    public Set<RenderSection> visibleSections() { return visibleSections; }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ACTIVE.remove();
        for (Map.Entry<RenderSection, Snapshot> entry : snapshots.entrySet()) {
            RenderSection section = entry.getKey();
            if (section.getLastVisibleFrame() == frame) visibleSections.add(section);
            Snapshot snapshot = entry.getValue();
            section.setLastVisibleFrame(snapshot.lastVisibleFrame());
            section.setIncomingDirections(snapshot.incomingDirections());
        }
    }

    private record Snapshot(int lastVisibleFrame, int incomingDirections) {
    }
}
