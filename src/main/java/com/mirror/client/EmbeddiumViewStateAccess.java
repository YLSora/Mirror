package com.mirror.client;

public interface EmbeddiumViewStateAccess {
    void mirror$releaseView(long viewId);
    void mirror$finishCapture(long viewId);
}
