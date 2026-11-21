package com.mirror.mixin;

import com.mirror.client.MirrorPipelineBuildContext;
import com.mirror.client.MirrorShadowBorrowCapability;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.pathways.HorizonRenderer;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

import java.util.EnumMap;
import java.util.Optional;
import net.irisshaders.iris.pipeline.FinalPassRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shadows.ShadowCompositeRenderer;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Sizes Iris render targets and relative images to the active mirror layer during construction. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
abstract class OculusIrisPipelineSizingMixin implements MirrorShadowBorrowCapability {
    @Shadow private HorizonRenderer horizonRenderer;
    @Shadow private RenderTargets renderTargets;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
    @Shadow private Set<GlImage> customImages;
    @Shadow @org.spongepowered.asm.mixin.Final @org.spongepowered.asm.mixin.Mutable private GlImage[] clearImages;
    @Shadow private CustomTextureManager customTextureManager;
    @Shadow private DynamicTexture whitePixel;
    @Shadow private CenterDepthSampler centerDepthSampler;
    @Shadow private CompositeRenderer beginRenderer;
    @Shadow private CompositeRenderer prepareRenderer;
    @Shadow private CompositeRenderer deferredRenderer;
    @Shadow private CompositeRenderer compositeRenderer;
    @Shadow private FinalPassRenderer finalPassRenderer;
    @Shadow private ShadowCompositeRenderer shadowCompositeRenderer;
    @Shadow private ShadowRenderer shadowRenderer;
    @Shadow private ShadowRenderTargets shadowRenderTargets;
    @Shadow private int shadowMapResolution;
    @Unique private ShaderPack mirror$shaderPack;
    @Unique private int mirror$noiseTextureResolution;
    @Shadow private ComputeProgram[] setup;
    @Shadow private ComputeProgram[] shadowComputes;
    @Shadow private Set<ShaderInstance> loadedShaders;
    @Unique private boolean mirror$constructedForReflection;
    @Unique private ShadowRenderTargets mirror$borrowedShadowTargets;
    @Unique private CustomTextureManager mirror$borrowedCustomTextureManager;

    @Unique private WorldRenderingPipeline mirror$primaryOwner;
    @Unique private final List<GlFramebuffer> mirror$adoptedShadowFramebuffers = new ArrayList<>();
    @Unique private final Set<GlImage> mirror$borrowedCustomImages = Collections.newSetFromMap(new IdentityHashMap<>());

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/transform/ShaderPrinter;resetPrintState()V"), require = 1)
    private void mirror$trackConstruction(Operation<Void> original) {
        if (MirrorPipelineBuildContext.target() != null) {
            mirror$constructedForReflection = true;
            mirror$borrowedShadowTargets = MirrorPipelineBuildContext.borrowedShadowTargets();
            mirror$borrowedCustomTextureManager = MirrorPipelineBuildContext.borrowedCustomTextureManager();
            mirror$borrowedCustomImages.addAll(MirrorPipelineBuildContext.borrowedCustomImages().values());
            mirror$primaryOwner = MirrorPipelineBuildContext.primaryPipeline();
            MirrorPipelineBuildContext.track(this::mirror$rollback);
        }
        original.call();
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void mirror$recordProgramSet(ProgramSet programs, CallbackInfo callback) {
        mirror$shaderPack = programs.getPack();
        mirror$noiseTextureResolution = programs.getPackDirectives().getNoiseTextureResolution();
        if (clearImages != null && !mirror$borrowedCustomImages.isEmpty()) {
            clearImages = Arrays.stream(clearImages).filter(image -> !mirror$borrowedCustomImages.contains(image))
                    .toArray(GlImage[]::new);
        }
    }

    @WrapOperation(method = "<init>", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;m_91385_()Lcom/mojang/blaze3d/pipeline/RenderTarget;"),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;")
    }, require = 1)
    private RenderTarget mirror$constructionTarget(Minecraft minecraft, Operation<RenderTarget> original) {
        RenderTarget target = MirrorPipelineBuildContext.target();
        return target != null ? target : original.call(minecraft);
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW",
            target = "net/irisshaders/iris/shadows/ShadowRenderTargets", remap = false), require = 1)
    private ShadowRenderTargets mirror$borrowConstructedShadowTargets(WorldRenderingPipeline owner, int resolution,
                                                                       PackShadowDirectives directives,
                                                                       Operation<ShadowRenderTargets> original) {
        ShadowRenderTargets borrowed = MirrorPipelineBuildContext.borrowedShadowTargets();
        if (borrowed == null) return original.call(owner, resolution, directives);
        mirror$borrowedShadowTargets = borrowed;
        return borrowed;
    }

    @WrapOperation(method = "lambda$new$4", at = @At(value = "NEW",
            target = "net/irisshaders/iris/shadows/ShadowRenderTargets", remap = false), require = 1)
    private ShadowRenderTargets mirror$borrowLazyShadowTargets(WorldRenderingPipeline owner, int resolution,
                                                                PackShadowDirectives directives,
                                                                Operation<ShadowRenderTargets> original) {
        ShadowRenderTargets borrowed = mirror$borrowedShadowTargets;
        if (borrowed == null) borrowed = MirrorPipelineBuildContext.borrowedShadowTargets();
        if (borrowed == null) return original.call(owner, resolution, directives);
        mirror$borrowedShadowTargets = borrowed;
        shadowRenderTargets = borrowed;
        return borrowed;
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW",
            target = "net/irisshaders/iris/pipeline/CustomTextureManager", remap = false), require = 1)
    private CustomTextureManager mirror$borrowCustomTextureManager(PackDirectives directives,
            EnumMap<TextureStage, Object2ObjectMap<String, CustomTextureData>> textures,
            Object2ObjectMap<String, CustomTextureData> irisTextures,
            Optional<CustomTextureData> noise, Operation<CustomTextureManager> original) {
        CustomTextureManager borrowed = MirrorPipelineBuildContext.borrowedCustomTextureManager();
        if (borrowed == null) return original.call(directives, textures, irisTextures, noise);
        mirror$borrowedCustomTextureManager = borrowed;
        return borrowed;
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW",
            target = "net/irisshaders/iris/gl/image/GlImage", remap = false), require = 1)
    private GlImage mirror$borrowAbsoluteCustomImage(String name, String samplerName,
            net.irisshaders.iris.gl.texture.TextureType target,
            net.irisshaders.iris.gl.texture.PixelFormat format,
            net.irisshaders.iris.gl.texture.InternalTextureFormat internalFormat,
            net.irisshaders.iris.gl.texture.PixelType pixelType, boolean clear,
            int width, int height, int depth, Operation<GlImage> original) {
        GlImage borrowed = MirrorPipelineBuildContext.borrowedCustomImage(name);
        if (borrowed == null) return original.call(name, samplerName, target, format, internalFormat,
                pixelType, clear, width, height, depth);
        mirror$borrowedCustomImages.add(borrowed);
        return borrowed;
    }

    @ModifyExpressionValue(method = "beginLevelRendering", at = @At(value = "FIELD",
            target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;shadowRenderTargets:" +
                    "Lnet/irisshaders/iris/shadows/ShadowRenderTargets;",
            opcode = Opcodes.GETFIELD, ordinal = 0, remap = false), require = 1, remap = false)
    private ShadowRenderTargets mirror$skipBorrowedShadowWrites(ShadowRenderTargets targets) {
        return mirror$usesBorrowedShadowTargets() ? null : targets;
    }

    @WrapOperation(method = "destroy", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;destroy()V", remap = false), require = 1)
    private void mirror$skipBorrowedShadowDestroy(ShadowRenderer renderer, Operation<Void> original) {
        if (!mirror$usesBorrowedShadowTargets()) original.call(renderer);
    }

    @WrapOperation(method = "destroy", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/CustomTextureManager;destroy()V", remap = false), require = 1)
    private void mirror$skipBorrowedCustomTextureDestroy(CustomTextureManager manager, Operation<Void> original) {
        if (!mirror$usesBorrowedCustomTextures()) original.call(manager);
    }

    @Unique
    private void mirror$rollback() {
        // shortcut: only resources published to pipeline fields are reachable; nested constructors need their own rollback for complete coverage.
        if (loadedShaders != null) for (ShaderInstance shader : loadedShaders) mirror$dispose(shader, ShaderInstance::close);
        mirror$destroyOmittedResources();
        mirror$dispose(shadowCompositeRenderer, ShadowCompositeRenderer::destroy);
        mirror$dispose(prepareRenderer, CompositeRenderer::destroy);
        mirror$dispose(deferredRenderer, CompositeRenderer::destroy);
        mirror$dispose(compositeRenderer, CompositeRenderer::destroy);
        mirror$dispose(finalPassRenderer, FinalPassRenderer::destroy);
        mirror$dispose(centerDepthSampler, CenterDepthSampler::destroy);
        if (!mirror$usesBorrowedCustomTextures()) {
            mirror$dispose(customTextureManager, CustomTextureManager::destroy);
        }
        mirror$dispose(whitePixel, DynamicTexture::close);
        mirror$dispose(horizonRenderer, HorizonRenderer::destroy);
        mirror$dispose(renderTargets, RenderTargets::destroy);
        if (customImages != null) for (GlImage image : customImages) {
            if (!mirror$borrowedCustomImages.contains(image)) mirror$dispose(image, GlImage::destroy);
        }
        if (!mirror$usesBorrowedShadowTargets()) mirror$dispose(shadowRenderer, ShadowRenderer::destroy);
        mirror$destroyAdoptedShadowFramebuffers();
        mirror$dispose(shaderStorageBufferHolder, ShaderStorageBufferHolder::destroyBuffers);
    }

    @Inject(method = "destroy", at = @At("HEAD"), require = 1)
    private void mirror$destroyExtraResources(CallbackInfo callback) {
        if (!mirror$constructedForReflection) return;
        if (customImages != null) customImages.removeAll(mirror$borrowedCustomImages);
        mirror$constructedForReflection = false;
        mirror$destroyOmittedResources();
        mirror$destroyAdoptedShadowFramebuffers();
    }

    @Unique
    private void mirror$destroyOmittedResources() {
        // Oculus 1.8.0's destroy() skips these resources and only releases shadow targets through ShadowRenderer.
        mirror$dispose(beginRenderer, CompositeRenderer::destroy);
        if (setup != null) for (ComputeProgram program : setup) mirror$dispose(program, ComputeProgram::destroy);
        if (shadowComputes != null) for (ComputeProgram program : shadowComputes) mirror$dispose(program, ComputeProgram::destroy);
        if (!mirror$usesBorrowedShadowTargets() && shadowRenderer == null) {
            mirror$dispose(shadowRenderTargets, ShadowRenderTargets::destroy);
        }
    }

    @Unique
    private void mirror$destroyAdoptedShadowFramebuffers() {
        for (GlFramebuffer framebuffer : mirror$adoptedShadowFramebuffers) {
            mirror$dispose(framebuffer, GlFramebuffer::destroy);
        }
        mirror$adoptedShadowFramebuffers.clear();
    }

    @Override
    public ShadowRenderTargets mirror$getShadowRenderTargets() {
        return shadowRenderTargets;
    }

    @Override
    public ShadowRenderer mirror$getShadowRenderer() {
        return shadowRenderer;
    }

    @Override
    public int mirror$getShadowMapResolution() {
        return shadowMapResolution;
    }

    @Override
    public CustomTextureManager mirror$getCustomTextureManager() {
        return customTextureManager;
    }

    @Override
    public ShaderPack mirror$getShaderPack() {
        return mirror$shaderPack;
    }

    @Override
    public int mirror$getNoiseTextureResolution() {
        return mirror$noiseTextureResolution;
    }

    @Override
    public Set<GlImage> mirror$getCustomImages() {
        return customImages;
    }

    @Override
    public boolean mirror$usesBorrowedCustomImages() {
        return !mirror$borrowedCustomImages.isEmpty();
    }

    @Override
    public boolean mirror$usesBorrowedCustomTextures() {
        return mirror$borrowedCustomTextureManager != null;
    }

    @Override
    public boolean mirror$usesBorrowedShadowTargets() {
        return mirror$borrowedShadowTargets != null;
    }

    @Override
    public WorldRenderingPipeline mirror$getPrimaryOwner() {
        return mirror$primaryOwner;
    }

    @Override
    public void mirror$setPrimaryOwner(WorldRenderingPipeline owner) {
        mirror$primaryOwner = owner;
    }

    @Override
    public void mirror$adoptShadowFramebuffers(List<GlFramebuffer> framebuffers) {
        mirror$adoptedShadowFramebuffers.addAll(framebuffers);
    }

    @Unique
    private static <T> void mirror$dispose(T resource, Consumer<T> cleanup) {
        if (resource == null) return;
        try {
            cleanup.accept(resource);
        } catch (Throwable failure) {
            Iris.logger.error("Could not release a mirror pipeline resource", failure);
        }
    }
}
