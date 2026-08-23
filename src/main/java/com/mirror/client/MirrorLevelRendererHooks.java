package com.mirror.client;

import com.mirror.bridge.LevelRendererBridge;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Future;

/** Builds per-texture visible-section state and restores Vanilla state after each pass. */
public final class MirrorLevelRendererHooks {
    private static final Map<Class<?>, Method> BOUNDING_BOX_METHODS = new HashMap<>();

    private MirrorLevelRendererHooks() {
    }

    public static State prepare(LevelRenderer renderer, Camera camera, Vec3 bfsStart,
                                TextureState textureState) {
        if (EmbeddiumCompat.ownsSectionCulling()) return null;
        LevelRendererBridge access = (LevelRendererBridge) renderer;
        ViewArea viewArea = access.mirror$getViewArea();
        if (viewArea == null || viewArea.chunks == null) return null;

        State state = new State(access);
        try {
            Object currentStorage = access.mirror$getRenderChunkStorage().get();
            Object isolatedStorage = textureState.getOrCreate(currentStorage, viewArea.chunks.length);
            Queue<Object> queue = new ArrayDeque<>();
            // Vanilla's occlusion graph uses the camera position both when seeding the BFS and
            // while propagating it. Keep that position identical for off-axis mirror passes;
            // using the reflected eye for propagation after seeding in front of the mirror can
            // reject whole sections at particular viewing angles.
            Vec3 cullingCameraPosition = bfsStart == null ? camera.getPosition() : bfsStart;
            initializeQueue(renderer, new SeedCamera(cullingCameraPosition, camera), queue);
            updateRenderChunks(renderer, isolatedStorage, cullingCameraPosition, queue,
                    Minecraft.getInstance().smartCull);
            access.mirror$getRenderChunkStorage().set(isolatedStorage);
            // setupRender is intercepted for reflection passes.  Its normal implementation would
            // rebuild this storage from the player's camera and discard the mirror BFS result.
            access.mirror$getNeedsFrustumUpdate().set(false);
            access.mirror$setNeedsFullRenderChunkUpdate(false);
            access.mirror$getNextFullUpdateMillis().set(Long.MAX_VALUE);
            return state;
        } catch (Throwable error) {
            state.close();
            throw new IllegalStateException("Unable to prepare isolated mirror chunk state", error);
        }
    }

    /**
     * Applies the frustum selected by LevelRenderer.renderLevel to the active texture's BFS result.
     * Vanilla normally does this from setupRender, which is deliberately skipped for mirrors so
     * that its player-camera update cannot replace the temporary storage.
     */
    public static void applyFrustum(LevelRenderer renderer, Frustum frustum) {
        LevelRendererBridge access = (LevelRendererBridge) renderer;
        Object storage = access.mirror$getRenderChunkStorage().get();
        if (storage == null) return;

        @SuppressWarnings("rawtypes")
        ObjectArrayList visible = access.mirror$getRenderChunksInFrustum();
        // Do not apply vanilla's camera-cube offset here.  That offset assumes a symmetric
        // camera frustum and can move an asymmetric mirror frustum away from the sections it was
        // built for.  The reflection pass prepares its own Frustum before entering renderLevel.
        Frustum cullingFrustum = new Frustum(frustum);
        visible.clear();
        try {
            Object chunks = fieldOfType(storage, Set.class);
            if (!(chunks instanceof Iterable<?> iterable)) return;
            for (Object info : iterable) {
                Object chunk = chunkFromInfo(info);
                if (chunk == null) continue;
                Method boundingBox = boundingBoxMethod(chunk.getClass());
                Object box = boundingBox.invoke(chunk);
                if (box instanceof AABB aabb && cullingFrustum.isVisible(aabb)) {
                    visible.add(info);
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to apply mirror chunk frustum", error);
        }
    }

    /** Persistent culling storage owned by one MirrorReflectionTexture. */
    public static final class TextureState {
        private Object storage;
        private int sectionCount = -1;

        private Object getOrCreate(Object vanillaStorage, int requiredSectionCount) throws Exception {
            if (storage == null || sectionCount != requiredSectionCount
                    || storage.getClass() != vanillaStorage.getClass()) {
                storage = newStorage(vanillaStorage, requiredSectionCount);
                sectionCount = requiredSectionCount;
            }
            return storage;
        }

        public void clear() {
            storage = null;
            sectionCount = -1;
        }
    }

    private static Method boundingBoxMethod(Class<?> chunkType) throws NoSuchMethodException {
        Method cached = BOUNDING_BOX_METHODS.get(chunkType);
        if (cached != null) return cached;

        // Development runs expose MCP names such as getBoundingBox, while a packaged Forge
        // client exposes the same public method under its SRG name. Its return signature is
        // stable, so resolve by the AABB contract instead of a mapping-specific name.
        for (Method candidate : chunkType.getMethods()) {
            if (candidate.getParameterCount() == 0
                    && AABB.class.isAssignableFrom(candidate.getReturnType())) {
                candidate.setAccessible(true);
                BOUNDING_BOX_METHODS.put(chunkType, candidate);
                return candidate;
            }
        }
        for (Class<?> type = chunkType; type != null; type = type.getSuperclass()) {
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.getParameterCount() == 0
                        && AABB.class.isAssignableFrom(candidate.getReturnType())) {
                    candidate.setAccessible(true);
                    BOUNDING_BOX_METHODS.put(chunkType, candidate);
                    return candidate;
                }
            }
        }
        throw new NoSuchMethodException(chunkType.getName() + ".<AABB-returning no-arg method>");
    }

    private static Object chunkFromInfo(Object info) throws IllegalAccessException {
        for (Field field : info.getClass().getDeclaredFields()) {
            if (field.getName().equals("chunk") || field.getType().getName().endsWith("$RenderChunk")) {
                field.setAccessible(true);
                return field.get(info);
            }
        }
        return null;
    }

    private static Object newStorage(Object current, int size) throws Exception {
        Constructor<?> constructor = current.getClass().getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(size);
    }

    private static void initializeQueue(LevelRenderer renderer, Camera camera, Queue<?> queue) throws Exception {
        Method method = null;
        // Forge's development and production environments expose different names for this method.
        for (Method candidate : LevelRenderer.class.getDeclaredMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0] == Camera.class
                    && acceptsQueue(parameters[1], queue.getClass())) {
                method = candidate;
                break;
            }
        }
        if (method == null) throw new NoSuchMethodException("LevelRenderer queue initialization method");
        method.setAccessible(true);
        method.invoke(renderer, camera, queue);
    }

    private static void updateRenderChunks(LevelRenderer renderer, Object storage,
                                            Vec3 cameraPosition, Queue<?> queue, boolean smartCull) throws Exception {
        Method target = findRenderChunkUpdateMethod(queue.getClass());
        Object chunks = fieldOfType(storage, target.getParameterTypes()[0]);
        Object infoMap = fieldOfType(storage, target.getParameterTypes()[1]);
        target.invoke(renderer, chunks, infoMap, cameraPosition, queue, smartCull);
    }

    private static Method findRenderChunkUpdateMethod(Class<?> queueType) throws NoSuchMethodException {
        // The parameter contract is stable across official and SRG runtime names.
        for (Method method : LevelRenderer.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 5
                    && Set.class.isAssignableFrom(parameters[0])
                    && parameters[2] == Vec3.class
                    && acceptsQueue(parameters[3], queueType)
                    && parameters[4] == boolean.class) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("LevelRenderer render chunk update method");
    }

    private static boolean acceptsQueue(Class<?> parameterType, Class<?> queueType) {
        return parameterType != Object.class
                && parameterType.isAssignableFrom(queueType)
                && (Queue.class.isAssignableFrom(parameterType)
                || parameterType.isAssignableFrom(Queue.class));
    }

    private static Object fieldOfType(Object target, Class<?> expectedType) throws Exception {
        Field match = null;
        for (Field field : target.getClass().getDeclaredFields()) {
            Class<?> actualType = field.getType();
            if (expectedType.isAssignableFrom(actualType)) {
                if (match != null) {
                    throw new NoSuchFieldException("Ambiguous " + expectedType.getName()
                            + " field in " + target.getClass().getName());
                }
                match = field;
            }
        }
        if (match == null) {
            throw new NoSuchFieldException("No " + expectedType.getName()
                    + " field in " + target.getClass().getName());
        }
        match.setAccessible(true);
        return match.get(target);
    }

    private static final class SeedCamera extends Camera {
        private SeedCamera(Vec3 position, Camera source) {
            setPosition(position);
            setRotation(source.getYRot(), source.getXRot());
        }
    }

    public static final class State implements AutoCloseable {
        private final LevelRendererBridge access;
        private final Object storage;
        private final List<?> visible;
        private final boolean needsFrustumUpdate;
        private final boolean needsFullRenderChunkUpdate;
        private final Future<?> lastFullRenderChunkUpdate;
        private final long nextFullUpdateMillis;
        private final int lastViewDistance;
        private final double lastCameraX, lastCameraY, lastCameraZ;
        private final int lastCameraChunkX, lastCameraChunkY, lastCameraChunkZ;
        private final double prevCamX, prevCamY, prevCamZ, prevCamRotX, prevCamRotY;
        private boolean restored;

        private State(LevelRendererBridge access) {
            this.access = access;
            this.storage = access.mirror$getRenderChunkStorage().get();
            this.visible = new ArrayList<>(access.mirror$getRenderChunksInFrustum());
            this.needsFrustumUpdate = access.mirror$getNeedsFrustumUpdate().get();
            this.needsFullRenderChunkUpdate = access.mirror$getNeedsFullRenderChunkUpdate();
            this.lastFullRenderChunkUpdate = access.mirror$getLastFullRenderChunkUpdate();
            this.nextFullUpdateMillis = access.mirror$getNextFullUpdateMillis().get();
            this.lastViewDistance = access.mirror$getLastViewDistance();
            this.lastCameraX = access.mirror$getLastCameraX();
            this.lastCameraY = access.mirror$getLastCameraY();
            this.lastCameraZ = access.mirror$getLastCameraZ();
            this.lastCameraChunkX = access.mirror$getLastCameraChunkX();
            this.lastCameraChunkY = access.mirror$getLastCameraChunkY();
            this.lastCameraChunkZ = access.mirror$getLastCameraChunkZ();
            this.prevCamX = access.mirror$getPrevCamX();
            this.prevCamY = access.mirror$getPrevCamY();
            this.prevCamZ = access.mirror$getPrevCamZ();
            this.prevCamRotX = access.mirror$getPrevCamRotX();
            this.prevCamRotY = access.mirror$getPrevCamRotY();
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void close() {
            if (restored) return;
            restored = true;
            access.mirror$getRenderChunkStorage().set(storage);
            ObjectArrayList current = access.mirror$getRenderChunksInFrustum();
            current.clear();
            current.addAll(visible);
            access.mirror$getNeedsFrustumUpdate().set(needsFrustumUpdate);
            access.mirror$setNeedsFullRenderChunkUpdate(needsFullRenderChunkUpdate);
            access.mirror$setLastFullRenderChunkUpdate(lastFullRenderChunkUpdate);
            access.mirror$getNextFullUpdateMillis().set(nextFullUpdateMillis);
            access.mirror$setLastViewDistance(lastViewDistance);
            access.mirror$setLastCameraX(lastCameraX);
            access.mirror$setLastCameraY(lastCameraY);
            access.mirror$setLastCameraZ(lastCameraZ);
            access.mirror$setLastCameraChunkX(lastCameraChunkX);
            access.mirror$setLastCameraChunkY(lastCameraChunkY);
            access.mirror$setLastCameraChunkZ(lastCameraChunkZ);
            access.mirror$setPrevCamX(prevCamX);
            access.mirror$setPrevCamY(prevCamY);
            access.mirror$setPrevCamZ(prevCamZ);
            access.mirror$setPrevCamRotX(prevCamRotX);
            access.mirror$setPrevCamRotY(prevCamRotY);
        }
    }
}
