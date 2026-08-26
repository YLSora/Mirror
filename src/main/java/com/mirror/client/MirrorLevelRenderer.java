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

    public static void clearContext() {
        RENDER_STACK.clear();
        mainBobEyeOffset = Vec3.ZERO;
    }

    public static void render(Level level, MirrorBlockEntity mirror, MirrorReflection reflection,
                              RenderTarget target, float partialTick) {
        Direction facing = mirror.getBlockState().getValue(com.mirror.common.MirrorBlock.FACING);
        render(level, mirror, reflection, target, partialTick, null, facing.toYRot(), 0.0f, 0, List.of());
    }

    public static void render(Level level, MirrorBlockEntity mirror, MirrorReflection reflection,
                              RenderTarget target, float partialTick, Matrix4f customProjection,
                              float yaw, float pitch, int recursionDepth, List<UUID> parentChain) {
        render(level, mirror, reflection, target, partialTick, customProjection, yaw, pitch,
                recursionDepth, parentChain, new MirrorLevelRendererHooks.TextureState());
    }

    static void render(Level level, MirrorBlockEntity mirror, MirrorReflection reflection,
                       RenderTarget target, float partialTick, Matrix4f customProjection,
                       float yaw, float pitch, int recursionDepth, List<UUID> parentChain,
                       MirrorLevelRendererHooks.TextureState textureState) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level || minecraft.player == null) return;

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        GameRenderer gameRenderer = minecraft.gameRenderer;
        MinecraftAccess minecraftAccess = minecraft instanceof MinecraftAccess access ? access : null;
        try (MirrorPassContext pass = MirrorPassContext.begin(recursionDepth, target)) {
            MirrorRenderState renderState = MirrorRenderState.capture();
            Camera camera = new MirrorCamera();
            RenderFrame frame = new RenderFrame(mirror.getId(), recursionDepth,
                    List.copyOf(parentChain), camera);
            GameRendererAccess rendererAccess = gameRenderer instanceof GameRendererAccess access ? access : null;
            BlockEntityRenderDispatcher blockEntityDispatcher = minecraft.getBlockEntityRenderDispatcher();
            EntityRenderDispatcher entityRenderDispatcher = minecraft.getEntityRenderDispatcher();
            OculusCompat.State oculusState = OculusCompat.capture(minecraft.levelRenderer);
            Camera previousMainCamera = rendererAccess == null ? gameRenderer.getMainCamera()
                    : rendererAccess.mirror$getMainCamera();
            ((MirrorCamera) camera).configure(level, reflection.reflectedEye(),
                    yaw, pitch, partialTick);

            float oldRenderDistance = gameRenderer.getRenderDistance();
            MirrorLevelRendererHooks.State cullState = null;
            boolean framePushed = false;
            try {
                oculusState.enterReflection();
                // Keep the normal Fabulous and post-processing paths intact. The active Iris
                // pipeline is isolated by the cache key and must observe the capture target as
                // an ordinary main target for this nested renderLevel call.
                if (minecraftAccess != null) minecraftAccess.mirror$setMainRenderTarget(target);
                target.bindWrite(true);
                RenderSystem.viewport(0, 0, target.width, target.height);
                RenderSystem.clear(16640, true);

                float fov = minecraft.options.fov().get().floatValue();
                Matrix4f projection = customProjection == null
                        ? new Matrix4f().perspective((float) Math.toRadians(fov),
                        (float) target.width / (float) target.height, 0.05f, 1000.0f)
                        : new Matrix4f(customProjection);
                RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);

                double distanceScale = Math.pow(MirrorConfig.CLIENT.recursiveRenderDistanceDecay.get(), recursionDepth);
                if (rendererAccess != null) {
                    // The mirror visibility distance controls whether the block entity is
                    // submitted; it is not the distance of the reflected world pass. Reuse the
                    // player's distance for depth zero and attenuate only nested passes.
                    rendererAccess.mirror$setRenderDistance((float) (oldRenderDistance * distanceScale));
                }

                if (rendererAccess != null) rendererAccess.mirror$setMainCamera(camera);
                // GameRenderer normally prepares both render dispatchers before LevelRenderer
                // is called. Mirror passes invoke LevelRenderer directly, so both must observe
                // the reflected camera for block-entity and entity submission.
                blockEntityDispatcher.prepare(level, camera, minecraft.hitResult);
                entityRenderDispatcher.prepare(level, camera, minecraft.crosshairPickEntity);
                RENDER_STACK.push(frame);
                framePushed = true;

                Direction facing = mirror.getBlockState().getValue(com.mirror.common.MirrorBlock.FACING);
                Vec3 normal = Vec3.atLowerCornerOf(facing.getNormal());
                Vec3 up = new Vec3(0, 1, 0);
                Vec3 right = normal.cross(up).normalize();
                double recession = com.mirror.common.MirrorBlock.surfaceRecession(mirror.getBlockState());
                Vec3 bfsStart = Vec3.atCenterOf(mirror.getBlockPos())
                        .add(normal.scale(0.5 - recession))
                        .add(right.scale((1.0 - mirror.getConnectedWidth()) * 0.5))
                        .add(up.scale((mirror.getConnectedHeight() - 1.0) * 0.5))
                        .add(normal);
                cullState = MirrorLevelRendererHooks.prepare(minecraft.levelRenderer, camera, bfsStart,
                        textureState);
                Matrix4f viewMatrix = new Matrix4f()
                        .rotationX((float) Math.toRadians(camera.getXRot()))
                        .rotateY((float) Math.toRadians(camera.getYRot() + 180.0f));
                Matrix3f viewNormal = new Matrix3f(viewMatrix);
                PoseStack poseStack = new PoseStack();
                poseStack.last().pose().set(viewMatrix);
                poseStack.last().normal().set(viewNormal);
                RenderSystem.setInverseViewRotationMatrix(new Matrix3f(poseStack.last().normal()).invert());
                minecraft.levelRenderer.prepareCullFrustum(poseStack, camera.getPosition(), projection);
                minecraft.levelRenderer.renderLevel(poseStack, partialTick, System.nanoTime(), false,
                        camera, gameRenderer, gameRenderer.lightTexture(), projection);
            } finally {
                if (cullState != null) cullState.close();
                if (framePushed) {
                    RenderFrame popped = RENDER_STACK.pop();
                    if (popped != frame) {
                        throw new IllegalStateException("Mirror render frames closed out of order");
                    }
                }
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
        }
    }

    private record RenderFrame(UUID mirrorId, int recursionDepth,
                               List<UUID> parentChain, Camera camera) {
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
