package com.mirror.client;

import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;

public interface EmbeddiumSectionStateAccess {
    void mirror$releaseView(long viewId);
    void mirror$beginView(Camera camera, Viewport viewport, int frame, boolean spectator);
    void mirror$endView();
}
