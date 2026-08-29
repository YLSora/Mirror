package com.mirror.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Render-thread context for one mirror pass.
 *
 * <p>The pipeline slot describes the stable Oculus shader-resource domain, while {@code viewId}
 * identifies one persistent reflected camera. View-specific matrices/camera history remain keyed by
 * {@code viewId}; heavyweight Oculus pipelines are shared by all views in the same slot.</p>
 */
public final class MirrorPassContext implements AutoCloseable {
    private static final ThreadLocal<Deque<MirrorPassContext>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private final long viewId;
    private final int recursionDepth;
    private final ResolutionBucket resolutionBucket;
    private final PipelineSlot pipelineSlot;
    private final RenderTarget captureTarget;
    private final float nearPlane;
    private final float renderDistance;
    private final MirrorProjection.UvRect reflectionCrop;
    private final Matrix4f previousModelView;
    private final Matrix4f previousProjection;
    private final Vec3 previousCameraPosition;
    private final Vec3 cullingOrigin;
    private boolean closed;

    private MirrorPassContext(long viewId, int recursionDepth, RenderTarget captureTarget,
                              float nearPlane, float renderDistance,
                              MirrorProjection.UvRect reflectionCrop,
                              Matrix4f previousModelView, Matrix4f previousProjection,
                              Vec3 previousCameraPosition, Vec3 cullingOrigin) {
        if (recursionDepth < 0) throw new IllegalArgumentException("recursionDepth must be non-negative");
        if (captureTarget.width <= 0 || captureTarget.height <= 0) {
            throw new IllegalArgumentException("mirror capture target must have a positive size");
        }
        if (!Float.isFinite(nearPlane) || nearPlane <= 0.0f) {
            throw new IllegalArgumentException("mirror near plane must be positive and finite");
        }
        if (!Float.isFinite(renderDistance) || renderDistance <= 0.0f) {
            throw new IllegalArgumentException("mirror render distance must be positive and finite");
        }
        this.viewId = viewId;
        this.recursionDepth = recursionDepth;
        this.resolutionBucket = new ResolutionBucket(captureTarget.width, captureTarget.height);
        this.pipelineSlot = new PipelineSlot(recursionDepth, resolutionBucket);
        this.captureTarget = captureTarget;
        this.nearPlane = nearPlane;
        this.renderDistance = renderDistance;
        this.reflectionCrop = reflectionCrop == null ? MirrorProjection.UvRect.full() : reflectionCrop;
        this.previousModelView = new Matrix4f(previousModelView);
        this.previousProjection = new Matrix4f(previousProjection);
        this.previousCameraPosition = previousCameraPosition;
        this.cullingOrigin = cullingOrigin;
    }

    public static MirrorPassContext begin(long viewId, int recursionDepth, RenderTarget captureTarget,
                                          float nearPlane, float renderDistance,
                                          MirrorProjection.UvRect reflectionCrop,
                                          Matrix4f previousModelView, Matrix4f previousProjection,
                                          Vec3 previousCameraPosition, Vec3 cullingOrigin) {
        MirrorPassContext context = new MirrorPassContext(
                viewId, recursionDepth, captureTarget, nearPlane, renderDistance, reflectionCrop,
                previousModelView, previousProjection, previousCameraPosition, cullingOrigin);
        STACK.get().push(context);
        return context;
    }

    public static boolean isActive() {
        return !STACK.get().isEmpty();
    }

    public static MirrorPassContext current() {
        MirrorPassContext context = STACK.get().peek();
        if (context == null) throw new IllegalStateException("No active mirror pass");
        return context;
    }

    public static PipelineSlot currentPipelineSlot() {
        return current().pipelineSlot;
    }


    /** Stable identity of the persistent reflected camera and its per-view temporal history. */
    public long viewId() {
        return viewId;
    }

    public int recursionDepth() {
        return recursionDepth;
    }

    public ResolutionBucket resolutionBucket() {
        return resolutionBucket;
    }

    public PipelineSlot pipelineSlot() {
        return pipelineSlot;
    }

    public RenderTarget captureTarget() {
        return captureTarget;
    }

    public float nearPlane() {
        return nearPlane;
    }

    /** Shader-pack far uniform, in blocks. The actual projection far plane is four times this. */
    public float renderDistance() {
        return renderDistance;
    }

    /** Physical mirror aperture within the centered capture target. */
    public MirrorProjection.UvRect reflectionCrop() {
        return reflectionCrop;
    }


    public Matrix4f previousModelView() {
        return new Matrix4f(previousModelView);
    }

    public Matrix4f previousProjection() {
        return new Matrix4f(previousProjection);
    }

    public Vec3 previousCameraPosition() {
        return previousCameraPosition;
    }

    /** Physical-world section used to seed chunk visibility for virtual mirror cameras. */
    public Vec3 cullingOrigin() {
        return cullingOrigin;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Deque<MirrorPassContext> contexts = STACK.get();
        if (contexts.peek() != this) {
            throw new IllegalStateException("Mirror pass contexts must close in stack order");
        }
        contexts.pop();
        if (contexts.isEmpty()) STACK.remove();
    }

    public record ResolutionBucket(int widthBucket, int heightBucket) {
    }

    public record PipelineSlot(int recursionDepth, ResolutionBucket resolution) {
    }
}
