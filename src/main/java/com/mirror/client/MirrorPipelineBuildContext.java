package com.mirror.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.RenderBuffers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Construction-only overrides for a secondary Iris pipeline. */
public final class MirrorPipelineBuildContext implements AutoCloseable {
    private static final ThreadLocal<MirrorPipelineBuildContext> CURRENT = new ThreadLocal<>();

    private final RenderTarget target;
    private final WorldRenderingPipeline primary;
    private final ShadowRenderTargets borrowedTargets;
    private final RenderBuffers borrowedBuffers;
    private final MirrorShadowRenderTargetsAccess targetAccess;
    private final boolean[] flippedSnapshot;
    private final Set<GlFramebuffer> framebufferSnapshot;
    private final String independentReason;
    private CustomTextureManager borrowedCustomTextureManager;
    private String customTextureIndependentReason;
    private Map<String, GlImage> borrowedCustomImages = Map.of();
    private Map<String, String> customImageIndependentReasons = Map.of();
    private Map<String, String> customImageScanSummary = Map.of();
    private List<GlFramebuffer> pendingAdopted = List.of();
    private Runnable rollback;
    private boolean finalized;
    private int adoptedFramebuffers;

    private MirrorPipelineBuildContext(RenderTarget target, WorldRenderingPipeline primary,
                                       ShadowRenderTargets borrowedTargets,
                                       RenderBuffers borrowedBuffers, MirrorShadowRenderTargetsAccess targetAccess,
                                       boolean[] flippedSnapshot, Set<GlFramebuffer> framebufferSnapshot,
                                       String independentReason) {
        this.target = target;
        this.primary = primary;
        this.borrowedTargets = borrowedTargets;
        this.borrowedBuffers = borrowedBuffers;
        this.targetAccess = targetAccess;
        this.flippedSnapshot = flippedSnapshot;
        this.framebufferSnapshot = framebufferSnapshot;
        this.independentReason = independentReason;
    }

    public static MirrorPipelineBuildContext begin(int depth, int size, WorldRenderingPipeline primary,
                                                   int mirrorShadowResolution, boolean sharingEnabled) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested mirror pipeline construction");

        IrisRenderingPipeline irisPrimary = primary instanceof IrisRenderingPipeline iris ? iris : null;
        MirrorShadowBorrowCapability capability = irisPrimary instanceof MirrorShadowBorrowCapability available
                ? available : null;
        ShadowRenderTargets targets = capability == null ? null : capability.mirror$getShadowRenderTargets();
        ShadowRenderer renderer = capability == null ? null : capability.mirror$getShadowRenderer();
        MirrorShadowRenderTargetsAccess targetsAccess = targets instanceof MirrorShadowRenderTargetsAccess access
                ? access : null;
        MirrorShadowRendererAccess rendererAccess = renderer instanceof MirrorShadowRendererAccess access
                ? access : null;
        boolean rendererBuildAvailable = renderer instanceof MirrorShadowRendererBuildCapability;
        int primaryResolution = capability == null ? 0 : capability.mirror$getShadowMapResolution();
        int targetResolution = targets == null ? 0 : targets.getResolution();
        String reason = MirrorShadowSharing.borrowFailure(sharingEnabled, irisPrimary != null,
                capability != null, targets != null, targetsAccess != null, renderer != null,
                rendererAccess != null, rendererBuildAvailable, primaryResolution, targetResolution, mirrorShadowResolution);

        boolean borrowing = reason == null;
        RenderBuffers buffers = borrowing ? rendererAccess.mirror$getBuffers() : null;
        if (borrowing && buffers == null) {
            borrowing = false;
            reason = "primary RenderBuffers unavailable";
        }
        boolean[] flippedSnapshot = borrowing
                ? MirrorShadowSharing.snapshotFlipped(targetsAccess.mirror$getFlipped()) : null;
        Set<GlFramebuffer> framebufferSnapshot = borrowing
                ? MirrorShadowSharing.identitySnapshot(targetsAccess.mirror$getOwnedFramebuffers()) : null;

        MirrorPipelineBuildContext scope = new MirrorPipelineBuildContext(
                MirrorCapturePool.acquireShader(depth,
                        size == 0 ? MirrorCapturePool.MIN_SHADER_LONG_EDGE : size).target(),
                primary, borrowing ? targets : null, buffers,
                borrowing ? targetsAccess : null, flippedSnapshot, framebufferSnapshot, reason);
        CURRENT.set(scope);
        return scope;
    }

    public static MirrorPipelineBuildContext beginIndependent(int depth, int size,
                                                              WorldRenderingPipeline primary, String reason) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested mirror pipeline construction");
        MirrorPipelineBuildContext scope = new MirrorPipelineBuildContext(
                MirrorCapturePool.acquireShader(depth,
                        size == 0 ? MirrorCapturePool.MIN_SHADER_LONG_EDGE : size).target(),
                primary, null, null, null, null, null, reason);
        CURRENT.set(scope);
        return scope;
    }

    public void configureCustomImageSharing(ProgramSet programs, List<MirrorCustomImageSharing.Source> sources,
                                             boolean sharingEnabled, String failedEarlierClass,
                                             boolean externalSourcesUnscanned) {
        MirrorShadowBorrowCapability capability = primary instanceof IrisRenderingPipeline
                && primary instanceof MirrorShadowBorrowCapability available ? available : null;
        Map<String, GlImage> primaryImages = new LinkedHashMap<>();
        if (capability != null && capability.mirror$getCustomImages() != null) {
            for (GlImage image : capability.mirror$getCustomImages()) {
                if (!(image instanceof GlImage.Relative)) primaryImages.put(image.getName(), image);
            }
        }
        Map<String, MirrorCustomImageSharing.Decision> scan = MirrorCustomImageSharing.scan(sources,
                programs.getPack().getIrisCustomImages().stream().map(ImageInformation::name).toList());
        Map<String, GlImage> borrowed = new LinkedHashMap<>();
        Map<String, String> independent = new LinkedHashMap<>();
        Map<String, String> summaries = new LinkedHashMap<>();
        for (ImageInformation image : programs.getPack().getIrisCustomImages()) {
            String name = image.name();
            String writeReason = externalSourcesUnscanned ? "external renderer shader sources unavailable"
                    : scan.get(name).reason();
            String reason = failedEarlierClass == null
                    ? MirrorCustomImageSharing.borrowFailure(sharingEnabled, !image.isRelative(),
                            primary instanceof IrisRenderingPipeline, capability != null,
                            capability != null && capability.mirror$getShaderPack() == programs.getPack(),
                            primaryImages.containsKey(name), !externalSourcesUnscanned,
                            scan.get(name).readOnly() && !externalSourcesUnscanned)
                    : "borrow failed earlier this generation: " + failedEarlierClass;
            if (reason == null) borrowed.put(name, primaryImages.get(name));
            else {
                independent.put(name, reason);
                summaries.put(name, reason + "; scan: " + writeReason);
            }
        }
        borrowedCustomImages = borrowed;
        customImageIndependentReasons = independent;
        customImageScanSummary = summaries;
    }

    public static GlImage borrowedCustomImage(String name) {
        MirrorPipelineBuildContext scope = CURRENT.get();
        return scope == null ? null : scope.borrowedCustomImages.get(name);
    }

    public static Map<String, GlImage> borrowedCustomImages() {
        MirrorPipelineBuildContext scope = CURRENT.get();
        return scope == null ? Map.of() : scope.borrowedCustomImages;
    }

    public boolean borrowsCustomImages() {
        return !borrowedCustomImages.isEmpty();
    }

    public List<String> sharedCustomImageNames() {
        return new ArrayList<>(borrowedCustomImages.keySet());
    }

    public List<String> independentCustomImageNames() {
        return new ArrayList<>(customImageIndependentReasons.keySet());
    }

    public Map<String, String> customImageScanSummary() {
        return customImageScanSummary;
    }

    public void configureCustomTextureSharing(Object mirrorPack, int mirrorNoiseResolution,
                                               boolean sharingEnabled, String failedEarlierClass) {
        MirrorShadowBorrowCapability capability = primary instanceof IrisRenderingPipeline
                && primary instanceof MirrorShadowBorrowCapability available ? available : null;
        CustomTextureManager manager = capability == null ? null : capability.mirror$getCustomTextureManager();
        String reason = failedEarlierClass == null
                ? MirrorShadowSharing.customTextureBorrowFailure(sharingEnabled,
                        primary instanceof IrisRenderingPipeline, capability != null, manager != null,
                        capability != null && capability.mirror$getShaderPack() == mirrorPack,
                        capability == null ? 0 : capability.mirror$getNoiseTextureResolution(), mirrorNoiseResolution)
                : "borrow failed earlier this generation: " + failedEarlierClass;
        if (reason == null) borrowedCustomTextureManager = manager;
        else customTextureIndependentReason = reason;
    }

    public static CustomTextureManager borrowedCustomTextureManager() {
        MirrorPipelineBuildContext scope = CURRENT.get();
        return scope == null ? null : scope.borrowedCustomTextureManager;
    }

    public static RenderTarget target() {
        MirrorPipelineBuildContext scope = CURRENT.get();
        return scope == null ? null : scope.target;
    }

    public static ShadowRenderTargets borrowedShadowTargets() {
        MirrorPipelineBuildContext scope = CURRENT.get();
        return scope == null ? null : scope.borrowedTargets;
    }

    public static RenderBuffers borrowedShadowBuffers() {
        MirrorPipelineBuildContext scope = CURRENT.get();
        return scope == null ? null : scope.borrowedBuffers;
    }

    public static WorldRenderingPipeline primaryPipeline() {
        MirrorPipelineBuildContext scope = CURRENT.get();
        return scope == null ? null : scope.primary;
    }

    public static void track(Runnable cleanup) {
        MirrorPipelineBuildContext scope = CURRENT.get();
        if (scope != null) scope.rollback = cleanup;
    }

    public boolean borrowsShadowTargets() {
        return borrowedTargets != null;
    }

    public String independentReason() {
        return independentReason;
    }

    public boolean borrowsCustomTextureManager() {
        return borrowedCustomTextureManager != null;
    }

    public String customTextureIndependentReason() {
        return customTextureIndependentReason;
    }

    public int adoptedFramebuffers() {
        return adoptedFramebuffers;
    }

    public void complete(WorldRenderingPipeline pipeline) {
        if (finalized) throw new IllegalStateException("Mirror pipeline build context already finalized");
        try {
            if (borrowsShadowTargets()) {
                pendingAdopted = detachAddedFramebuffers();
                if (!(pipeline instanceof MirrorShadowBorrowCapability capability)
                        || !capability.mirror$usesBorrowedShadowTargets()) {
                    throw new IllegalStateException("Mirror shadow-safety capability missing on constructed pipeline");
                }
                capability.mirror$adoptShadowFramebuffers(pendingAdopted);
                adoptedFramebuffers = pendingAdopted.size();
                pendingAdopted = List.of();
            }
            if (borrowsCustomTextureManager()
                    && (!(pipeline instanceof MirrorShadowBorrowCapability capability)
                    || !capability.mirror$usesBorrowedCustomTextures())) {
                throw new IllegalStateException("Mirror custom-texture safety capability missing on constructed pipeline");
            }
            if (borrowsCustomImages()
                    && (!(pipeline instanceof MirrorShadowBorrowCapability capability)
                    || !capability.mirror$usesBorrowedCustomImages())) {
                throw new IllegalStateException("Mirror custom-image safety capability missing on constructed pipeline");
            }
        } catch (RuntimeException | Error failure) {
            if (borrowsShadowTargets()) {
                destroy(pendingAdopted);
                pendingAdopted = List.of();
                try {
                    destroy(detachAddedFramebuffers());
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        } finally {
            finalized = true;
        }
    }

    public void rollback() {
        if (finalized) return;
        try {
            if (borrowsShadowTargets()) {
                destroy(pendingAdopted);
                pendingAdopted = List.of();
                destroy(detachAddedFramebuffers());
            }
        } finally {
            finalized = true;
            if (rollback != null) rollback.run();
        }
    }

    private List<GlFramebuffer> detachAddedFramebuffers() {
        try {
            return MirrorShadowSharing.detachAdded(targetAccess.mirror$getOwnedFramebuffers(), framebufferSnapshot);
        } finally {
            MirrorShadowSharing.restoreFlipped(targetAccess.mirror$getFlipped(), flippedSnapshot);
        }
    }

    private static void destroy(List<GlFramebuffer> framebuffers) {
        for (GlFramebuffer framebuffer : framebuffers) {
            try {
                framebuffer.destroy();
            } catch (Throwable failure) {
                Iris.logger.error("Could not release an adopted mirror shadow framebuffer", failure);
            }
        }
    }

    @Override
    public void close() {
        try {
            if (!finalized) rollback();
        } finally {
            if (CURRENT.get() == this) CURRENT.remove();
        }
    }
}
