package com.mirror.compat;

import me.cortex.voxy.client.core.gl.GlTexture;

public record VoxyColors(GlTexture color, GlTexture ssao) {
    public void free() {
        color.free();
        ssao.free();
    }
}
