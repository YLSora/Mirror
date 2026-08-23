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
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix3f;

import java.util.UUID;
import java.util.List;

/** Minimal state-isolated second LevelRenderer pass for direct reflections. */
public final class MirrorLevelRenderer {
    private static boolean renderingReflection;
    private static UUID currentMirrorId;
    private static int currentRecursionDepth;
    private static List<UUID> currentParentChain = List.of();
    private static Camera activeCamera;
    private static Vec3 mainBobEyeOffset = Vec3.ZERO;

    private MirrorLevelRenderer() {
    }

    public static boolean isRenderingReflection() {
        return renderingReflection;
    }

    public static void setRenderingReflection(boolean value) {
        renderingReflection = value;
    }

    public static boolean isCurrentMirror(MirrorBlockEntity mirror) {
        return currentMirrorId != null && currentMirrorId.equals(mirror.getId());
    }

    public static boolean isMirrorInCurrentChain(MirrorBlockEntity mirror) {
        return isCurrentMirror(mirror) || currentParentChain.contains(mirror.getId());
    }

    public static Camera getActiveCamera() {
        return activeCamera;
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
        return currentRecursionDepth + 1;
    }

    public static List<UUID> getChildParentChain() {
        List<UUID> chain = new java.util.ArrayList<>(currentParentChain);
        if (currentMirrorId != null) chain.add(currentMirrorId);
        return List.copyOf(chain);
    }

    public static void clearContext() {
        currentMirrorId = null;
        currentRecursionDepth = 0;
        currentParentChain = List.of();
        activeCamera = null;
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
        MirrorRenderState renderState = MirrorRenderState.capture();
        FabulousDeferredState fabulousState = null;
        Camera camera = new MirrorCamera();
        UUID previousMirrorId = currentMirrorId;
        int previousDepth = currentRecursionDepth;
        List<UUID> previousParentChain = currentParentChain;
        Camera previousCamera = activeCamera;
        GameRendererAccess rendererAccess = gameRenderer instanceof GameRendererAccess access ? access : null;
        OculusCompat.State oculusState = OculusCompat.capture();
        PostChain previousPostEffect = rendererAccess == null ? null : rendererAccess.mirror$getPostEffect();
        boolean previousEffectActive = rendererAccess != null && rendererAccess.mirror$isEffectActive();
        Camera previousMainCamera = rendererAccess == null ? gameRenderer.getMainCamera()
                : rendererAccess.mirror$getMainCamera();
        ((MirrorCamera) camera).configure(level, reflection.reflectedEye(),
                yaw, pitch, partialTick);

        float oldRenderDistance = gameRenderer.getRenderDistance();
        MirrorLevelRendererHooks.State cullState = null;
        try {
            oculusState.enterReflection();
            // A post effect owns the main framebuffer and its shader uniforms.  Leaving it active
            // during an off-screen world pass makes Oculus sample the mirror target as if it were
            // the main frame, which is the source of the one-frame-later black screen.
            if (rendererAccess != null) {
                rendererAccess.mirror$setPostEffect(null);
                rendererAccess.mirror$setEffectActive(false);
            }
            fabulousState = FabulousDeferredState.captureAndDisable(minecraft.levelRenderer);
            target.bindWrite(true);
            if (minecraftAccess != null) minecraftAccess.mirror$setMainRenderTarget(target);
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
                // The mirror visibility distance controls whether the block entity is submitted;
                // it is not the distance of the reflected world pass.  Capping the direct pass to
                // that value (48 blocks by default) drops the far sections of a near mirror's
                // wide off-axis view even though those sections are already loaded for the main
                // world.  Reuse the player's world distance for depth zero and only attenuate
                // nested passes, which keeps direct reflections complete without changing the
                // user's normal render-distance setting.
                rendererAccess.mirror$setRenderDistance((float) (oldRenderDistance * distanceScale));
            }

            currentMirrorId = mirror.getId();
            currentRecursionDepth = recursionDepth;
            currentParentChain = List.copyOf(parentChain);
            activeCamera = camera;
            if (rendererAccess != null) rendererAccess.mirror$setMainCamera(camera);

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
            // The accessor-backed culling state is part of the same transaction as the
            // framebuffer/camera changes.  If a mixin is unavailable or chunk setup fails,
            // the finally block must still restore the main render pass.
            cullState = MirrorLevelRendererHooks.prepare(minecraft.levelRenderer, camera, bfsStart,
                    textureState);
            // Match GameRenderer's world-to-view transform. Camera.rotation() maps the local
            // forward (+Z) vector into the world; the extra 180-degree yaw is the vanilla
            // projection convention that turns that forward vector into OpenGL's -Z view axis.
            // Omitting it leaves the reflected player behind the frustum when the mirror faces
            // north/south, even though the scene appears to render.
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
            if (fabulousState != null) fabulousState.restore();
            target.unbindWrite();
            if (minecraftAccess != null) minecraftAccess.mirror$setMainRenderTarget(mainTarget);
            mainTarget.bindWrite(true);
            renderState.restore();
            if (rendererAccess != null) {
                rendererAccess.mirror$setRenderDistance(oldRenderDistance);
                rendererAccess.mirror$setMainCamera(previousMainCamera);
                rendererAccess.mirror$setPostEffect(previousPostEffect);
                rendererAccess.mirror$setEffectActive(previousEffectActive);
            }
            oculusState.close();
            currentMirrorId = previousMirrorId;
            currentRecursionDepth = previousDepth;
            currentParentChain = previousParentChain;
            activeCamera = previousCamera;
        }
    }

    private static final class MirrorCamera extends Camera {
        private void configure(Level level, net.minecraft.world.phys.Vec3 position,
                               float yaw, float pitch, float partialTick) {
            // A detached virtual entity keeps the reflected camera independent from the player.
            // LevelRenderer can then draw the real player as an ordinary scene entity, matching
            // Vista's mirror implementation.
            Display.BlockDisplay dummy = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
            dummy.setPos(position);
            dummy.setYRot(yaw);
            dummy.setXRot(pitch);
            setup(level, dummy, true, false, partialTick);
            setPosition(position);
            setRotation(yaw, pitch);
        }
    }
}
