package com.mirror.client;

import java.util.function.Consumer;

/** GL-free owner/vertex-format stash for primary terrain programs while mirror programs are active. */
public final class MirrorPrimaryProgramStash<T> {
    private final Consumer<T> disposer;
    private Object pipeline;
    private Object vertexType;
    private T value;

    public MirrorPrimaryProgramStash(Consumer<T> disposer) {
        this.disposer = disposer;
    }

    public void store(Object owner, Object format, T programs) {
        if (value != null && value != programs) disposer.accept(value);
        pipeline = owner;
        vertexType = format;
        value = programs;
    }

    public T take(Object owner, Object format) {
        if (value == null) return null;
        if (pipeline != owner || vertexType != format) {
            clear();
            return null;
        }
        T result = value;
        pipeline = null;
        vertexType = null;
        value = null;
        return result;
    }

    /** Transfers any stored program map to Oculus' own deleteShaders() path. */
    public T drain() {
        T result = value;
        pipeline = null;
        vertexType = null;
        value = null;
        return result;
    }

    public void clear() {
        if (value != null) disposer.accept(value);
        pipeline = null;
        vertexType = null;
        value = null;
    }

    public boolean isEmpty() {
        return value == null;
    }
}
