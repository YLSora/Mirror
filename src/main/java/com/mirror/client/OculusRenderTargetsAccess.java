package com.mirror.client;

/** Requests the next Iris level-begin clear to use the full render-target clear set. */
public interface OculusRenderTargetsAccess {
    void mirror$requestFullClear();
}
