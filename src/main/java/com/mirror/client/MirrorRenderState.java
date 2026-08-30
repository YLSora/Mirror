package com.mirror.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mirror.mixin.PoseStackAccess;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Complete render-thread snapshot for one off-screen world pass. */
final class MirrorRenderState {
    private final Matrix4f projection;
    private final Matrix4f savedProjection;
    private final VertexSorting vertexSorting;
    private final VertexSorting savedVertexSorting;
    private final Matrix3f inverseViewRotation;
    private final List<PoseData> modelViewStack;
    private final Matrix4f textureMatrix;
    private final int[] viewport;
    private final ScissorState scissorState;
    private final int vertexArray;
    private final int arrayBuffer;
    private final int elementArrayBuffer;
    private final int pixelPackBuffer;
    private final int pixelUnpackBuffer;
    private final int drawFramebuffer;
    private final int readFramebuffer;
    private final int drawBuffer;
    private final int readBuffer;
    private final int activeTexture;
    private final int[] textures;
    private final int[] texture2dBindings;
    private final ShaderInstance shader;
    private final int program;
    private final float[] shaderColor;
    private final float fogStart;
    private final float fogEnd;
    private final float[] fogColor;
    private final FogShape fogShape;
    private final float glintAlpha;
    private final float lineWidth;
    private final float shaderGameTime;
    private final Vector3f[] shaderLights;
    private final boolean depthTest;
    private final boolean blend;
    private final boolean cull;
    private final boolean polygonOffset;
    private final boolean depthMask;
    private final int depthFunc;
    private final int blendSrcRgb;
    private final int blendDstRgb;
    private final int blendSrcAlpha;
    private final int blendDstAlpha;
    private final int blendEquation;
    private final boolean[] colorMask;
    private final float[] clearColor;
    private final float clearDepth;
    private final int clearStencil;

    private MirrorRenderState() {
        projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        savedProjection = copyRenderSystemMatrix("savedProjectionMatrix");
        vertexSorting = RenderSystem.getVertexSorting();
        savedVertexSorting = readRenderSystemField("savedVertexSorting", VertexSorting.class);
        inverseViewRotation = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
        modelViewStack = captureModelViewStack();
        textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
        shader = RenderSystem.getShader();
        program = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        shaderColor = RenderSystem.getShaderColor().clone();
        fogStart = RenderSystem.getShaderFogStart();
        fogEnd = RenderSystem.getShaderFogEnd();
        fogColor = RenderSystem.getShaderFogColor().clone();
        fogShape = RenderSystem.getShaderFogShape();
        glintAlpha = RenderSystem.getShaderGlintAlpha();
        lineWidth = RenderSystem.getShaderLineWidth();
        shaderGameTime = RenderSystem.getShaderGameTime();
        shaderLights = captureShaderLights();

        IntBuffer viewportBuffer = BufferUtils.createIntBuffer(4);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewportBuffer);
        viewport = new int[]{viewportBuffer.get(0), viewportBuffer.get(1),
                viewportBuffer.get(2), viewportBuffer.get(3)};
        scissorState = captureScissorState();
        vertexArray = GL30C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL15C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        elementArrayBuffer = GL15C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        pixelPackBuffer = GL21C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        pixelUnpackBuffer = GL21C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        drawFramebuffer = GL30C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer = GL30C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        drawBuffer = GL11C.glGetInteger(GL11C.GL_DRAW_BUFFER);
        readBuffer = GL11C.glGetInteger(GL11C.GL_READ_BUFFER);
        activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        // VertexBuffer copies all twelve RenderSystem shader-texture slots into every vanilla
        // ShaderInstance before drawing. Preserve the same complete logical sampler state; restoring
        // only the first four slots lets a nested world pass leak its higher sampler assignments into
        // later item and GUI draws.
        textures = new int[12];
        for (int unit = 0; unit < textures.length; unit++) {
            textures[unit] = RenderSystem.getShaderTexture(unit);
        }
        // Minecraft's GlStateManager exposes 128 texture slots, but its active-texture
        // bookkeeping is not safe at the driver-reported upper bound. Iris/Oculus uses the
        // low sampler range for all world and composite passes; restore that complete range.
        int textureUnitCount = Math.min(32,
                Math.max(1, GL20C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)));
        texture2dBindings = new int[textureUnitCount];
        for (int unit = 0; unit < textureUnitCount; unit++) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
            texture2dBindings[unit] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        }
        GL13C.glActiveTexture(activeTexture);
        depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        polygonOffset = GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL);
        depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        blendSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        blendDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        blendSrcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        blendDstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        blendEquation = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB);
        colorMask = captureColorMask();
        clearColor = captureClearColor();
        clearDepth = GL11C.glGetFloat(GL11C.GL_DEPTH_CLEAR_VALUE);
        clearStencil = GL11C.glGetInteger(GL11C.GL_STENCIL_CLEAR_VALUE);
    }

    static MirrorRenderState capture() {
        return new MirrorRenderState();
    }

    void restore() {
        RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        scissorState.restore();
        // BufferUploader caches the last bound VertexBuffer in its static lastImmediateBuffer field
        // and skips glBindVertexArray when the next draw reuses that same VertexBuffer, assuming its
        // VAO is still bound. We change the VAO binding directly below (bypassing VertexBuffer.bind),
        // so without invalidating that cache the first-person hand/item pass can draw with a stale
        // "already bound" assumption and hit GL_INVALID_OPERATION "Array object is not active".
        BufferUploader.invalidate();
        GlStateManager._glBindVertexArray(vertexArray);
        GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer);
        GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer);
        GlStateManager._glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, pixelPackBuffer);
        GlStateManager._glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL11C.glDrawBuffer(drawBuffer);
        GL11C.glReadBuffer(readBuffer);
        RenderSystem.setProjectionMatrix(projection, vertexSorting);
        restoreRenderSystemField("savedProjectionMatrix", savedProjection);
        restoreRenderSystemField("savedVertexSorting", savedVertexSorting);
        restoreModelViewStack(modelViewStack);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setInverseViewRotationMatrix(inverseViewRotation);
        RenderSystem.setTextureMatrix(textureMatrix);
        RenderSystem.setShader(() -> shader);
        // Oculus 1.8.0 tracks _glUseProgram in a static redundant-bind cache. Restore both the
        // driver binding and that cache before the next outer frame.
        GL20C.glUseProgram(0);
        GlStateManager._glUseProgram(0);
        GL20C.glUseProgram(program);
        GlStateManager._glUseProgram(program);
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        RenderSystem.setShaderFogShape(fogShape);
        RenderSystem.setShaderGlintAlpha(glintAlpha);
        RenderSystem.lineWidth(lineWidth);
        restoreShaderGameTime(shaderGameTime);
        restoreShaderLights(shaderLights);
        for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, textures[i]);
        for (int unit = 0; unit < texture2dBindings.length; unit++) {
            RenderSystem.activeTexture(GL13C.GL_TEXTURE0 + unit);
            if (unit < 12) {
                RenderSystem.bindTexture(texture2dBindings[unit]);
            } else {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture2dBindings[unit]);
            }
        }
        RenderSystem.activeTexture(activeTexture);

        RenderSystem.depthFunc(depthFunc);
        RenderSystem.depthMask(depthMask);
        RenderSystem.blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        RenderSystem.blendEquation(blendEquation);
        RenderSystem.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        GL11C.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        GL11C.glClearDepth(clearDepth);
        GL11C.glClearStencil(clearStencil);
        setState(GL11C.GL_DEPTH_TEST, depthTest, RenderSystem::enableDepthTest, RenderSystem::disableDepthTest);
        setState(GL11C.GL_BLEND, blend, RenderSystem::enableBlend, RenderSystem::disableBlend);
        setState(GL11C.GL_CULL_FACE, cull, RenderSystem::enableCull, RenderSystem::disableCull);
        setState(GL11C.GL_POLYGON_OFFSET_FILL, polygonOffset,
                RenderSystem::enablePolygonOffset, RenderSystem::disablePolygonOffset);
    }


    static ScissorState captureScissorState() {
        IntBuffer scissorBuffer = BufferUtils.createIntBuffer(4);
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBuffer);
        return new ScissorState(
                GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
                scissorBuffer.get(0), scissorBuffer.get(1),
                scissorBuffer.get(2), scissorBuffer.get(3));
    }

    record ScissorState(boolean enabled, int x, int y, int width, int height) {
        void restore() {
            if (enabled) {
                RenderSystem.enableScissor(x, y, width, height);
            } else {
                RenderSystem.disableScissor();
            }
        }
    }

    private static boolean[] captureColorMask() {
        ByteBuffer mask = BufferUtils.createByteBuffer(4);
        GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, mask);
        return new boolean[]{mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0};
    }

    private static float[] captureClearColor() {
        java.nio.FloatBuffer value = BufferUtils.createFloatBuffer(4);
        GL11C.glGetFloatv(GL11C.GL_COLOR_CLEAR_VALUE, value);
        return new float[]{value.get(0), value.get(1), value.get(2), value.get(3)};
    }

    private static void restoreShaderGameTime(float value) {
        float scaled = value * 24000.0f;
        long ticks = (long) Math.floor(scaled);
        RenderSystem.setShaderGameTime(ticks, scaled - ticks);
    }

    private static Vector3f[] captureShaderLights() {
        Vector3f[] lights = readRenderSystemField("shaderLightDirections", Vector3f[].class);
        if (lights == null || lights.length < 2) return null;
        return new Vector3f[]{new Vector3f(lights[0]), new Vector3f(lights[1])};
    }

    private static void restoreShaderLights(Vector3f[] lights) {
        if (lights != null) RenderSystem.setShaderLights(lights[0], lights[1]);
    }

    private static List<PoseData> captureModelViewStack() {
        try {
            Deque<?> stack = ((PoseStackAccess) (Object) RenderSystem.getModelViewStack()).mirror$getPoseStack();
            List<PoseData> result = new ArrayList<>(stack.size());
            for (Object value : stack) {
                PoseStack.Pose pose = (PoseStack.Pose) value;
                result.add(new PoseData(new Matrix4f(pose.pose()), new Matrix3f(pose.normal())));
            }
            return result;
        } catch (RuntimeException ignored) {
            PoseStack.Pose pose = RenderSystem.getModelViewStack().last();
            return List.of(new PoseData(new Matrix4f(pose.pose()), new Matrix3f(pose.normal())));
        }
    }

    private static void restoreModelViewStack(List<PoseData> values) {
        try {
            PoseStack stack = RenderSystem.getModelViewStack();
            Deque<PoseStack.Pose> target = ((PoseStackAccess) (Object) stack).mirror$getPoseStack();
            Constructor<PoseStack.Pose> constructor = PoseStack.Pose.class
                    .getDeclaredConstructor(Matrix4f.class, Matrix3f.class);
            constructor.setAccessible(true);
            target.clear();
            for (PoseData value : values) target.addLast(constructor.newInstance(value.pose(), value.normal()));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            PoseStack.Pose pose = RenderSystem.getModelViewStack().last();
            PoseData value = values.get(values.size() - 1);
            pose.pose().set(value.pose());
            pose.normal().set(value.normal());
        }
    }

    private static Matrix4f copyRenderSystemMatrix(String name) {
        Matrix4f value = readRenderSystemField(name, Matrix4f.class);
        return value == null ? null : new Matrix4f(value);
    }

    private static <T> T readRenderSystemField(String name, Class<T> type) {
        try {
            Field field = RenderSystem.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(null);
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void restoreRenderSystemField(String name, Object value) {
        try {
            Field field = RenderSystem.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The public RenderSystem state has already been restored; this is an optional cache.
        }
    }

    private static void setState(int capability, boolean enabled, Runnable enable, Runnable disable) {
        if (GL11C.glIsEnabled(capability) != enabled) {
            if (enabled) enable.run();
            else disable.run();
        }
    }

    private record PoseData(Matrix4f pose, Matrix3f normal) {
    }
}
