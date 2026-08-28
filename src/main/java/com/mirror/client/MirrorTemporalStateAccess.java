package com.mirror.client;

/** View-lifetime hook for temporal resources owned by a shared mirror pipeline slot. */
public interface MirrorTemporalStateAccess {
    void mirror$releaseView(long viewId);
}
