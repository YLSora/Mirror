package com.mirror.client;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.blending.BlendModeStorage;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
/** Oculus state boundary, loaded only when Oculus is present. */
public final class OculusCompatImpl implements OculusCompat.Runtime {
    private static final String TERRAIN_OVERRIDE_CLASS =
            "net.irisshaders.iris.compat.sodium.impl.shader_overrides.IrisChunkProgramOverrides";
    private static final String TERRAIN_OVERRIDE_METHOD = "getProgramOverride";
    private static final String TERRAIN_CREATE_METHOD = "createShaders";

    private static volatile Field levelRendererPipeline;

    @Override
    public void initialize() {
        verifyPipelineAccess();
        // The terrain override mixin is version-specific. Verify its target at startup so an
        // Oculus API drift fails before the first world frame.
        if (ModList.get().isLoaded("embeddium")) verifyTerrainOverrideTarget();
    }

    @Override
    public void beginMirrorFrame() {
        PipelineManager manager = Iris.getPipelineManager();
        if (!(manager instanceof MirrorPipelineAccess access)) {
            throw new IllegalStateException("Mirror PipelineManager state access is unavailable");
        }
        access.mirror$beginFrame();
    }

    @Override
    public void releaseMirrorView(long viewId) {
        PipelineManager manager = Iris.getPipelineManager();
        if (!(manager instanceof MirrorPipelineAccess access)) {
            throw new IllegalStateException("Mirror PipelineManager state access is unavailable");
        }
        access.mirror$releaseMirrorView(viewId);
    }

    @Override
    public boolean isShaderPackInUse() {
        if (!IrisApi.getInstance().isShaderPackInUse()) return false;
        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        // Oculus keeps the pack selected when pipeline creation fails, but replaces the active
        // world pipeline with its vanilla fallback. Treat only a real IrisRenderingPipeline as
        // shader-active so mirrors do not retry the same failed pack for every slot/view.
        return current instanceof IrisRenderingPipeline;
    }

    @Override
    public boolean isShadowPass() {
        return IrisApi.getInstance().isRenderingShadowPass();
    }

    @Override
    public Object getLevelRendererPipeline(LevelRenderer renderer) {
        try {
            return levelRendererPipelineField().get(renderer);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot read Oculus LevelRenderer.pipeline", error);
        }
    }

    @Override
    public void setLevelRendererPipeline(LevelRenderer renderer, Object pipeline) {
        try {
            levelRendererPipelineField().set(renderer, pipeline);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot write Oculus LevelRenderer.pipeline", error);
        }
    }

    private static Field levelRendererPipelineField() {
        Field cached = levelRendererPipeline;
        if (cached != null) return cached;
        try {
            Field field = LevelRenderer.class.getDeclaredField("pipeline");
            if (Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException("Oculus LevelRenderer.pipeline is unexpectedly static");
            }
            field.setAccessible(true);
            levelRendererPipeline = field;
            return field;
        } catch (NoSuchFieldException error) {
            throw new IllegalStateException("Oculus LevelRenderer.pipeline is unavailable", error);
        }
    }

    @Override
    public OculusCompat.Transaction capture(LevelRenderer renderer) {
        PipelineManager manager = Iris.getPipelineManager();
        if (manager == null) throw new IllegalStateException("Oculus PipelineManager is unavailable");
        if (!(manager instanceof MirrorPipelineAccess pipelineAccess)) {
            throw new IllegalStateException("Mirror PipelineManager state access is unavailable");
        }
        if (!(renderer instanceof OculusLevelRendererAccess levelAccess)) {
            throw new IllegalStateException("Mirror LevelRenderer pipeline access is unavailable");
        }

        CapturedRenderingState captured = CapturedRenderingState.INSTANCE;
        return new MirrorTransaction(
                pipelineAccess,
                levelAccess,
                pipelineAccess.mirror$getPipeline(),
                levelAccess.mirror$getPipeline(),
                captured,
                new CapturedSnapshot(captured),
                ShadowState.capture(),
                ImmediateState.isRenderingLevel,
                ImmediateState.usingTessellation,
                ImmediateState.renderWithExtendedVertexFormat,
                Minecraft.getInstance().smartCull);
    }

    private static void verifyPipelineAccess() {
        PipelineManager manager = Iris.getPipelineManager();
        if (!(manager instanceof MirrorPipelineAccess)) {
            throw new IllegalStateException("Oculus PipelineManager mixin was not applied");
        }
    }

    private static void verifyTerrainOverrideTarget() {
        try {
            Class<?> target = Class.forName(TERRAIN_OVERRIDE_CLASS, false,
                    OculusCompatImpl.class.getClassLoader());
            if (!OculusTerrainProgramCacheAccess.class.isAssignableFrom(target)) {
                throw new IllegalStateException("Oculus terrain program cache mixin was not applied");
            }
            boolean overrideFound = false;
            boolean createShadersFound = false;
            for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (TERRAIN_OVERRIDE_METHOD.equals(method.getName())
                        && parameters.length == 2
                        && "me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass"
                        .equals(parameters[0].getName())
                        && "me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType"
                        .equals(parameters[1].getName())
                        && "me.jellysquid.mods.sodium.client.gl.shader.GlProgram"
                        .equals(method.getReturnType().getName())) {
                    overrideFound = true;
                } else if (TERRAIN_CREATE_METHOD.equals(method.getName())
                        && method.getReturnType() == void.class) {
                    // The mixin deliberately does not capture target arguments. Keep the startup
                    // check tied to the stable method/return boundary instead of assuming arity.
                    createShadersFound = true;
                }
            }
            if (!overrideFound || !createShadersFound) {
                throw new IllegalStateException("Oculus terrain override method signature changed");
            }
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Oculus terrain override class is unavailable", error);
        }
    }

    private static final class MirrorTransaction implements OculusCompat.Transaction {
        private final MirrorPipelineAccess pipelineAccess;
        private final OculusLevelRendererAccess levelAccess;
        private final WorldRenderingPipeline outerManagerPipeline;
        private final Object outerLevelPipeline;
        private final CapturedRenderingState captured;
        private final CapturedSnapshot capturedSnapshot;
        private final ShadowState shadowState;
        private final boolean outerRenderingLevel;
        private final boolean outerUsingTessellation;
        private final boolean outerExtendedVertexFormat;
        private final boolean outerSmartCull;
        private boolean entered;
        private boolean restored;

        private MirrorTransaction(MirrorPipelineAccess pipelineAccess,
                                  OculusLevelRendererAccess levelAccess,
                                  WorldRenderingPipeline outerManagerPipeline,
                                  Object outerLevelPipeline,
                                  CapturedRenderingState captured,
                                  CapturedSnapshot capturedSnapshot,
                                  ShadowState shadowState,
                                  boolean outerRenderingLevel,
                                  boolean outerUsingTessellation,
                                  boolean outerExtendedVertexFormat,
                                  boolean outerSmartCull) {
            this.pipelineAccess = pipelineAccess;
            this.levelAccess = levelAccess;
            this.outerManagerPipeline = outerManagerPipeline;
            this.outerLevelPipeline = outerLevelPipeline;
            this.captured = captured;
            this.capturedSnapshot = capturedSnapshot;
            this.shadowState = shadowState;
            this.outerRenderingLevel = outerRenderingLevel;
            this.outerUsingTessellation = outerUsingTessellation;
            this.outerExtendedVertexFormat = outerExtendedVertexFormat;
            this.outerSmartCull = outerSmartCull;
        }

        @Override
        public void enterReflection() {
            if (entered) return;
            entered = true;
            BlendModeStorage.restoreBlend();
            DepthColorStorage.unlockDepthColor();
            OculusCompat.beginMirrorTransaction();
        }

        @Override
        public void close() {
            if (restored) return;
            restored = true;
            try {
                ProgramUniforms.clearActiveUniforms();
                ProgramSamplers.clearActiveSamplers();
                BlendModeStorage.restoreBlend();
                DepthColorStorage.unlockDepthColor();
                capturedSnapshot.restore(captured);
                shadowState.restore();
                ImmediateState.isRenderingLevel = outerRenderingLevel;
                ImmediateState.usingTessellation = outerUsingTessellation;
                ImmediateState.renderWithExtendedVertexFormat = outerExtendedVertexFormat;
                Minecraft.getInstance().smartCull = outerSmartCull;
                levelAccess.mirror$setPipeline(outerLevelPipeline);
                pipelineAccess.mirror$setPipeline(outerManagerPipeline);
            } finally {
                if (entered) {
                    OculusCompat.endMirrorTransaction();
                }
            }
        }
    }

    private record CapturedSnapshot(Matrix4f modelView, Matrix4f projection, Vector3d fogColor,
                                    float fogDensity, float darknessLightFactor, float tickDelta,
                                    float realTickDelta, int currentBlockEntity, int currentEntity,
                                    int currentItem, float currentAlphaTest, float cloudTime) {
        private CapturedSnapshot(CapturedRenderingState state) {
            this(new Matrix4f(state.getGbufferModelView()), new Matrix4f(state.getGbufferProjection()),
                    new Vector3d(state.getFogColor()), state.getFogDensity(),
                    state.getDarknessLightFactor(), state.getTickDelta(), state.getRealTickDelta(),
                    state.getCurrentRenderedBlockEntity(), state.getCurrentRenderedEntity(),
                    state.getCurrentRenderedItem(), state.getCurrentAlphaTest(), state.getCloudTime());
        }

        private void restore(CapturedRenderingState state) {
            state.setGbufferModelView(new Matrix4f(modelView));
            state.setGbufferProjection(new Matrix4f(projection));
            state.setFogColor((float) fogColor.x, (float) fogColor.y, (float) fogColor.z);
            state.setFogDensity(fogDensity);
            state.setDarknessLightFactor(darknessLightFactor);
            state.setTickDelta(tickDelta);
            state.setRealTickDelta(realTickDelta);
            state.setCurrentBlockEntity(currentBlockEntity);
            state.setCurrentEntity(currentEntity);
            state.setCurrentRenderedItem(currentItem);
            state.setCurrentAlphaTest(currentAlphaTest);
            state.setCloudTime(cloudTime);
        }
    }

    private record ShadowState(boolean active, java.util.List<net.minecraft.world.level.block.entity.BlockEntity> visible,
                               int renderDistance, Matrix4f modelView, Matrix4f projection,
                               net.minecraft.client.renderer.culling.Frustum frustum) {
        private static ShadowState capture() {
            return new ShadowState(ShadowRenderer.ACTIVE,
                    ShadowRenderer.visibleBlockEntities == null ? null
                            : java.util.List.copyOf(ShadowRenderer.visibleBlockEntities),
                    ShadowRenderer.renderDistance,
                    ShadowRenderer.MODELVIEW == null ? null : new Matrix4f(ShadowRenderer.MODELVIEW),
                    ShadowRenderer.PROJECTION == null ? null : new Matrix4f(ShadowRenderer.PROJECTION),
                    ShadowRenderer.FRUSTUM);
        }

        private void restore() {
            ShadowRenderer.ACTIVE = active;
            if (visible == null) ShadowRenderer.visibleBlockEntities = null;
            else ShadowRenderer.visibleBlockEntities = new java.util.ArrayList<>(visible);
            ShadowRenderer.renderDistance = renderDistance;
            ShadowRenderer.MODELVIEW = modelView == null ? null : new Matrix4f(modelView);
            ShadowRenderer.PROJECTION = projection == null ? null : new Matrix4f(projection);
            ShadowRenderer.FRUSTUM = frustum;
        }
    }
}
