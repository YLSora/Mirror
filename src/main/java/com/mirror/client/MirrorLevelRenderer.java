package com.mirror.client;

import com.mirror.common.MirrorBlockEntity;
import com.mirror.config.MirrorConfig;
import com.mirror.mixin.GameRendererAccess;
import com.mirror.mixin.MinecraftAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.List;

/** Minimal state-isolated second LevelRenderer pass for direct reflections. */
public final class MirrorLevelRenderer {
    private static final Deque<RenderFrame> RENDER_STACK = new ArrayDeque<>();
    private static Vec3 mainBobEyeOffset = Vec3.ZERO;

    private MirrorLevelRenderer() {
    }

    public static boolean isRenderingReflection() {
        return !RENDER_STACK.isEmpty();
    }

    public static Camera getActiveCamera() {
        RenderFrame frame = RENDER_STACK.peek();
        return frame == null ? null : frame.camera();
    }

    public static boolean isRecursivePass() {
        RenderFrame frame = RENDER_STACK.peek();
        return frame != null && frame.recursionDepth() > 0;
    }

    public static boolean isParentMirror(UUID mirrorId) {
        RenderFrame frame = RENDER_STACK.peek();
        return frame != null && frame.parentChain().contains(mirrorId);
    }

    public static Vec3 getMainBobEyeOffset() {
        return mainBobEyeOffset;
    }

    public static void captureMainBobEyeOffset(Camera camera, Matrix4f bobPose) {
        org.joml.Vector3f offset = new Matrix4f(bobPose).invert().getTranslation(new org.joml.Vector3f());
        camera.rotation().transform(offset);
        mainBobEyeOffset = new Vec3(offset.x(), offset.y(), offset.z());
    }

    public static int getChildDepth() {
        RenderFrame frame = RENDER_STACK.peek();
        return frame == null ? 0 : frame.recursionDepth() + 1;
    }

    public static List<UUID> getChildParentChain() {
        RenderFrame frame = RENDER_STACK.peek();
        if (frame == null) return List.of();
        List<UUID> chain = new java.util.ArrayList<>(frame.parentChain());
        chain.add(frame.mirrorId());
        return List.copyOf(chain);
    }

    /** Returns the static mirror planes that transform the main eye into the current reflected eye. */
    public static List<ReflectionPlane> getChildReflectionPath() {
        RenderFrame frame = RENDER_STACK.peek();
        return frame == null ? List.of() : frame.reflectionPath();
    }

    /** Rebuilds a recursive camera from the current outer-frame eye instead of stale prior-frame coordinates. */
    public static Vec3 resolveReflectionPath(Vec3 mainEye, List<ReflectionPlane> path) {
        Vec3 eye = mainEye;
        for (ReflectionPlane plane : path) {
            MirrorReflection reflection = MirrorReflection.compute(plane.point(), plane.normal(), eye);
            if (!reflection.viewerInFront()) return null;
            eye = reflection.reflectedEye();
        }
        return eye;
    }

    public static void clearContext() {
        RENDER_STACK.clear();
        mainBobEyeOffset = Vec3.ZERO;
    }

    public static void render(Level level, MirrorBlockEntity mirror, MirrorReflection reflection,
                              RenderTarget target, float partialTick) {
        Direction facing = mirror.getBlockState().getValue(com.mirror.common.MirrorBlock.FACING);
        render(level, mirror, reflection, target, partialTick, null, facing.toYRot(), 0.0f, 0, List.of(),
                List.of(), new MirrorLevelRendererHooks.TextureState(), Long.MIN_VALUE, new MirrorViewHistory());
    }

    public static void render(Level level, MirrorBlockEntity mirror, MirrorReflection reflection,
                              RenderTarget target, float partialTick, MirrorProjection.ViewportProjection customProjection,
                              float yaw, float pitch, int recursionDepth, List<UUID> parentChain) {
        render(level, mirror, reflection, target, partialTick, customProjection, yaw, pitch,
                recursionDepth, parentChain, List.of(), new MirrorLevelRendererHooks.TextureState(),
                Long.MIN_VALUE, new MirrorViewHistory());
    }

    static void render(Level level, MirrorBlockEntity mirror, MirrorReflection reflection,
                       RenderTarget target, float partialTick, MirrorProjection.ViewportProjection customProjection,
                       float yaw, float pitch, int recursionDepth, List<UUID> parentChain,
                       List<ReflectionPlane> parentReflectionPath,
                       MirrorLevelRendererHooks.TextureState textureState,
                       long viewId, MirrorViewHistory viewHistory) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level || minecraft.player == null) return;

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        GameRenderer gameRenderer = minecraft.gameRenderer;
        MinecraftAccess minecraftAccess = minecraft instanceof MinecraftAccess access ? access : null;
        float oldRenderDistance = gameRenderer.getRenderDistance();
        Vec3 reflectedEye = reflection.reflectedEye();
        Vec3 playerEye = gameRenderer.getMainCamera().getPosition();
        Direction mirrorFacing = mirror.getBlockState().getValue(com.mirror.common.MirrorBlock.FACING);
        Vec3 mirrorNormal = Vec3.atLowerCornerOf(mirrorFacing.getNormal());
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = mirrorNormal.cross(up).normalize();
        double recession = com.mirror.common.MirrorBlock.surfaceRecession(mirror.getBlockState());
        Vec3 mirrorPlanePoint = Vec3.atCenterOf(mirror.getBlockPos()).add(mirrorNormal.scale(
                0.5 - recession));
        // A mirror camera is virtual and can move outside Embeddium's physical SectionTree after
        // several reflections. Seed section traversal immediately in front of the physical mirror
        // instead of at that virtual eye; the reflected frustum still performs the actual visibility
        // test once traversal reaches geometry inside the aperture.
        Vec3 cullingOrigin = Vec3.atCenterOf(mirror.getBlockPos())
                .add(mirrorNormal.scale(0.5 - recession))
                .add(right.scale((1.0 - mirror.getConnectedWidth()) * 0.5))
                .add(up.scale((mirror.getConnectedHeight() - 1.0) * 0.5))
                .add(mirrorNormal);
        // The reflected camera recedes from the player at deeper recursion levels (a mirror tunnel
        // moves the eye by the mirror separation each reflection). Anchor the render/search distance
        // to the player's loaded chunk range instead of decaying it toward zero, otherwise the deep
        // mirror-in-mirror views cull every loaded section and render only the sky.
        float mirrorRenderDistance = Math.max(oldRenderDistance,
                oldRenderDistance + (float) reflectedEye.distanceTo(playerEye));
        float nearPlane = customProjection == null ? 0.05f : customProjection.nearPlane();
        float farPlane = Math.max(nearPlane + 1.0f, mirrorRenderDistance * 4.0f);
        MirrorProjection.UvRect reflectionCrop = customProjection == null
                ? MirrorProjection.UvRect.full() : customProjection.crop();
        float fov = minecraft.options.fov().get().floatValue();
        Matrix4f projection = customProjection == null
                ? new Matrix4f().perspective((float) Math.toRadians(fov),
                (float) target.width / (float) target.height, nearPlane, farPlane)
                : customProjection.matrix(farPlane);
        Matrix4f viewMatrix = new Matrix4f()
                .rotationX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0f));
        MirrorViewHistory.Snapshot previous = viewHistory.previousOr(viewMatrix, projection, reflectedEye);
        try (MirrorPassContext pass = MirrorPassContext.begin(
                viewId, recursionDepth, target, nearPlane, mirrorRenderDistance, reflectionCrop,
                previous.modelView(), previous.projection(), previous.cameraPosition(), cullingOrigin)) {
            MirrorRenderState renderState = MirrorRenderState.capture();
            Camera camera = new MirrorCamera();
            List<ReflectionPlane> reflectionPath = new java.util.ArrayList<>(parentReflectionPath);
            reflectionPath.add(new ReflectionPlane(mirrorPlanePoint, mirrorNormal));
            RenderFrame frame = new RenderFrame(mirror.getId(), recursionDepth,
                    List.copyOf(parentChain), camera, List.copyOf(reflectionPath));
            GameRendererAccess rendererAccess = gameRenderer instanceof GameRendererAccess access ? access : null;
            BlockEntityRenderDispatcher blockEntityDispatcher = minecraft.getBlockEntityRenderDispatcher();
            EntityRenderDispatcher entityRenderDispatcher = minecraft.getEntityRenderDispatcher();
            OculusCompat.State oculusState = OculusCompat.capture(minecraft.levelRenderer);
            Camera previousMainCamera = rendererAccess == null ? gameRenderer.getMainCamera()
                    : rendererAccess.mirror$getMainCamera();
            ((MirrorCamera) camera).configure(level, reflectedEye, yaw, pitch, partialTick);

            MirrorLevelRendererHooks.State cullState = null;
            boolean framePushed = false;
            boolean renderedFrame = false;
            boolean oldSmartCull = minecraft.smartCull;
            DeferredMirrorSurfaceRenderer.PassScope deferredSurfaces = null;
            try {
                oculusState.enterReflection();
                // The reflected camera sits at a virtual mirror-image position. Physical
                // occlusion culling around that position is meaningless: deep reflections
                // recede behind the mirror into solid geometry, where Embeddium's smart
                // occlusion graph blocks the traversal and leaves the capture with only the
                // clear color (sky). Disable smart culling so every recursion depth reaches
                // the loaded sections the mirror actually reflects.
                minecraft.smartCull = false;
                // Nested mirror textures are already fully composed images. Queue their physical
                // surfaces until this mirror pipeline reaches finalizeLevelRendering, otherwise an
                // entity/emissive G-buffer pass shades and tone-maps the child reflection twice.
                deferredSurfaces = DeferredMirrorSurfaceRenderer.beginMirrorPass();
                // Keep the normal Fabulous and post-processing paths intact. The active Iris
                // pipeline is isolated by the cache key and must observe the capture target as
                // an ordinary main target for this nested renderLevel call.
                if (minecraftAccess != null) minecraftAccess.mirror$setMainRenderTarget(target);
                target.bindWrite(true);
                RenderSystem.viewport(0, 0, target.width, target.height);
                // A shader pipeline may leave a partial scissor rectangle active. Reusing that
                // rectangle on a smaller mirror target spatially truncates the reflection. World
                // rendering owns the full capture target; restore the outer scissor with renderState.
                RenderSystem.disableScissor();
                RenderSystem.clear(16640, true);

                RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);

                if (rendererAccess != null) {
                    // The mirror visibility distance controls whether the block entity is
                    // submitted; it is not the distance of the reflected world pass. Reuse the
                    // player's distance for depth zero and attenuate only nested passes.
                    rendererAccess.mirror$setRenderDistance(mirrorRenderDistance);
                }

                if (rendererAccess != null) rendererAccess.mirror$setMainCamera(camera);
                // GameRenderer normally prepares both render dispatchers before LevelRenderer
                // is called. Mirror passes invoke LevelRenderer directly, so both must observe
                // the reflected camera for block-entity and entity submission.
                blockEntityDispatcher.prepare(level, camera, minecraft.hitResult);
                entityRenderDispatcher.prepare(level, camera, minecraft.crosshairPickEntity);
                RENDER_STACK.push(frame);
                framePushed = true;

                cullState = MirrorLevelRendererHooks.prepare(minecraft.levelRenderer, camera, cullingOrigin,
                        textureState);
                Matrix3f viewNormal = new Matrix3f(viewMatrix);
                PoseStack poseStack = new PoseStack();
                poseStack.last().pose().set(viewMatrix);
                poseStack.last().normal().set(viewNormal);
                RenderSystem.setInverseViewRotationMatrix(new Matrix3f(poseStack.last().normal()).invert());
                minecraft.levelRenderer.prepareCullFrustum(poseStack, camera.getPosition(), projection);
                minecraft.levelRenderer.renderLevel(poseStack, partialTick, System.nanoTime(), false,
                        camera, gameRenderer, gameRenderer.lightTexture(), projection);
                renderedFrame = true;
            } finally {
                if (cullState != null) cullState.close();
                if (deferredSurfaces != null) deferredSurfaces.close();
                if (framePushed) {
                    RenderFrame popped = RENDER_STACK.pop();
                    if (popped != frame) {
                        throw new IllegalStateException("Mirror render frames closed out of order");
                    }
                }
                minecraft.smartCull = oldSmartCull;
                if (minecraftAccess != null) minecraftAccess.mirror$setMainRenderTarget(mainTarget);
                // Restore the exact outer Oculus pipeline and captured state before the outer
                // framebuffer is bound. Iris' RenderTarget listener reads the active pipeline at
                // bind time.
                oculusState.close();
                renderState.restore();
                if (rendererAccess != null) {
                    rendererAccess.mirror$setRenderDistance(oldRenderDistance);
                    rendererAccess.mirror$setMainCamera(previousMainCamera);
                }
                blockEntityDispatcher.prepare(level, previousMainCamera, minecraft.hitResult);
                entityRenderDispatcher.prepare(level, previousMainCamera, minecraft.crosshairPickEntity);
                mainTarget.bindWrite(true);
                RenderSystem.viewport(0, 0, mainTarget.width, mainTarget.height);
            }
            if (renderedFrame) {
                viewHistory.commit(viewMatrix, projection, reflectedEye);
            }
        }
    }

    public record ReflectionPlane(Vec3 point, Vec3 normal) {
    }

    private record RenderFrame(UUID mirrorId, int recursionDepth,
                               List<UUID> parentChain, Camera camera,
                               List<ReflectionPlane> reflectionPath) {
    }

    private static final class MirrorCamera extends Camera {
        private Display.BlockDisplay cameraEntity;

        private void configure(Level level, net.minecraft.world.phys.Vec3 position,
                               float yaw, float pitch, float partialTick) {
            // Keep the reflected camera internally self-consistent. The dummy entity is never
            // added to the level; it only supplies Camera#getEntity for vanilla/Forge renderer
            // state that expects the camera entity to occupy the camera's actual position.
            if (cameraEntity == null || cameraEntity.level() != level) {
                cameraEntity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
            }
            cameraEntity.setPos(position);
            cameraEntity.setYRot(yaw);
            cameraEntity.setXRot(pitch);

            setup(level, cameraEntity, true, false, partialTick);
            setPosition(position);
            setRotation(yaw, pitch);
        }
    }
}
