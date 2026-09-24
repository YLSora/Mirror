package com.mirror.client;

import com.mojang.blaze3d.platform.GlStateManager;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.util.HashMap;
import java.util.Map;

/** GPU history only; compiled pipelines, framebuffer texture names and samplers stay stable. */
public final class OculusMirrorHistory {
    private final Map<Long, Map<Integer, RenderTarget>> saved = new HashMap<>();
    private long resident = -1;
    private boolean complete;
    private GlFramebuffer read;
    private GlFramebuffer draw;

    public void leave(long nextView, RenderTargets targets, Map<Integer, RenderTargetSettings> settings) {
        if (resident == nextView) return;
        if (resident >= 0 && complete) {
            Map<Integer, RenderTarget> snapshot = saved.computeIfAbsent(resident, ignored -> new HashMap<>());
            copy(targets, snapshot, settings, true);
        }
        resident = -1;
        complete = false;
    }

    public void enter(long view, RenderTargets targets, Map<Integer, RenderTargetSettings> settings) {
        // Reallocations or a new capture-depth owner invalidate every image in this slot.
        if (targets.isFullClearRequired()) clear();
        if (resident != view || !complete) {
            Map<Integer, RenderTarget> snapshot = saved.get(view);
            if (snapshot != null && matches(targets, snapshot, settings)) {
                copy(targets, snapshot, settings, false);
            } else {
                release(view);
                ((OculusRenderTargetsAccess) (Object) targets).mirror$requestFullClear();
                MirrorDiagnostics.recordTemporalAttachmentReset();
            }
        }
        resident = view;
        complete = false;
    }

    public void commit() { complete = true; }

    public void release(long view) {
        Map<Integer, RenderTarget> snapshot = saved.remove(view);
        if (snapshot != null) snapshot.values().forEach(RenderTarget::destroy);
        if (resident == view) {
            resident = -1;
            complete = false;
        }
    }

    public void clear() {
        saved.values().forEach(snapshot -> snapshot.values().forEach(RenderTarget::destroy));
        saved.clear();
        resident = -1;
        complete = false;
    }

    public void destroy() {
        clear();
        if (read != null) read.destroy();
        if (draw != null) draw.destroy();
        read = null;
        draw = null;
    }

    private static boolean matches(RenderTargets targets, Map<Integer, RenderTarget> snapshot,
                                   Map<Integer, RenderTargetSettings> settings) {
        for (var entry : settings.entrySet()) {
            RenderTarget source = targets.get(entry.getKey());
            if (entry.getValue().shouldClear() || source == null) continue;
            RenderTarget image = snapshot.get(entry.getKey());
            if (image == null || image.getWidth() != source.getWidth() || image.getHeight() != source.getHeight()
                    || image.getInternalFormat() != source.getInternalFormat()) return false;
        }
        return true;
    }

    private void copy(RenderTargets targets, Map<Integer, RenderTarget> snapshot,
                      Map<Integer, RenderTargetSettings> settings, boolean saving) {
        int oldRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean srgb = GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB);
        try {
            if (read == null) {
                read = new GlFramebuffer();
                draw = new GlFramebuffer();
                read.readBuffer(0);
                draw.drawBuffers(new int[]{0});
            }
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
            for (var entry : settings.entrySet()) {
                RenderTarget live = targets.get(entry.getKey());
                if (entry.getValue().shouldClear() || live == null) continue;
                RenderTarget image = snapshot.get(entry.getKey());
                if (saving && (image == null || image.getWidth() != live.getWidth()
                        || image.getHeight() != live.getHeight() || image.getInternalFormat() != live.getInternalFormat())) {
                    if (image != null) image.destroy();
                    image = RenderTarget.builder().setDimensions(live.getWidth(), live.getHeight())
                            .setInternalFormat(live.getInternalFormat())
                            .setPixelFormat(live.getInternalFormat().getPixelFormat())
                            .setPixelType(pixelType(live)).build();
                    snapshot.put(entry.getKey(), image);
                }
                RenderTarget source = saving ? live : image;
                RenderTarget destination = saving ? image : live;
                blit(source.getMainTexture(), destination.getMainTexture(), live.getWidth(), live.getHeight());
                blit(source.getAltTexture(), destination.getAltTexture(), live.getWidth(), live.getHeight());
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, oldRead);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, oldDraw);
            GlStateManager._bindTexture(oldTexture);
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            if (srgb) GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB);
        }
    }

    private void blit(int source, int destination, int width, int height) {
        read.addColorAttachment(0, source);
        draw.addColorAttachment(0, destination);
        read.bindAsReadBuffer();
        draw.bindAsDrawBuffer();
        GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
    }

    private static PixelType pixelType(RenderTarget target) {
        String format = target.getInternalFormat().name();
        if (format.endsWith("F")) return format.contains("16") ? PixelType.HALF_FLOAT : PixelType.FLOAT;
        if (format.endsWith("UI")) return format.contains("8") ? PixelType.UNSIGNED_BYTE
                : format.contains("16") ? PixelType.UNSIGNED_SHORT : PixelType.UNSIGNED_INT;
        if (format.endsWith("I")) return format.contains("8") ? PixelType.BYTE
                : format.contains("16") ? PixelType.SHORT : PixelType.INT;
        if (format.contains("16")) return PixelType.UNSIGNED_SHORT;
        if (format.contains("32")) return PixelType.UNSIGNED_INT;
        return PixelType.UNSIGNED_BYTE;
    }
}
