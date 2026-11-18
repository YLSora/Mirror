package com.mirror.client;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Render-thread identity cache used by the Oculus terrain-program bridge.
 *
 * <p>The value is kept outside IrisChunkProgramOverrides instances because Embeddium recreates
 * that object whenever its chunk renderer is rebuilt.</p>
 */
public final class OculusTerrainProgramCacheStore<T> {
    private final Map<Object, Entry<T>> entries = new IdentityHashMap<>();
    private final Consumer<T> disposer;

    public OculusTerrainProgramCacheStore(Consumer<T> disposer) {
        this.disposer = disposer;
    }

    public Lookup<T> take(Object pipeline, Object vertexType) {
        Entry<T> entry = entries.remove(pipeline);
        if (entry == null) return new Lookup<>(null, false, false);
        if (entry.vertexType() != vertexType) {
            disposer.accept(entry.value());
            return new Lookup<>(null, true, true);
        }
        return new Lookup<>(entry.value(), true, false);
    }

    public void put(Object pipeline, Object vertexType, T value) {
        Entry<T> previous = entries.put(pipeline, new Entry<>(vertexType, value));
        if (previous != null && previous.value() != value) disposer.accept(previous.value());
    }

    public void remove(Object pipeline) {
        Entry<T> previous = entries.remove(pipeline);
        if (previous != null) disposer.accept(previous.value());
    }

    public void clear() {
        entries.values().forEach(entry -> disposer.accept(entry.value()));
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public record Lookup<T>(T value, boolean hadEntry, boolean vertexTypeMismatch) {
    }

    private record Entry<T>(Object vertexType, T value) {
    }
}
