package com.mirror.client;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;

/** Restored at the end of the complete world capture, including block entities. */
public record EmbeddiumViewSetupState(long viewId, Viewport outerViewport, RenderSectionManager manager) {
}
