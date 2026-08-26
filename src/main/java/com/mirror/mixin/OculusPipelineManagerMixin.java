package com.mirror.mixin;

import com.mirror.client.MirrorPassContext;
import com.mirror.client.MirrorPipelineAccess;
import com.mirror.client.OculusCompat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Keeps mirror cache identity separate from the real shader-pack dimension. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
abstract class OculusPipelineManagerMixin implements MirrorPipelineAccess {
    @Shadow
    @Final
    private Function<NamespacedId, WorldRenderingPipeline> pipelineFactory;

    @Shadow
    private WorldRenderingPipeline pipeline;

    @Shadow
    private int versionCounterForSodiumShaderReload;

    @Unique
    private final Map<MirrorPipelineKey, WorldRenderingPipeline> mirror$mirrorPipelines = new HashMap<>();

    @Inject(method = "preparePipeline", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$preparePipeline(NamespacedId realDimension,
                                        CallbackInfoReturnable<WorldRenderingPipeline> callback) {
        if (!OculusCompat.isMirrorPass()) return;

        MirrorPipelineKey key = new MirrorPipelineKey(realDimension, MirrorPassContext.currentPipelineSlot());
        WorldRenderingPipeline selected = mirror$mirrorPipelines.get(key);
        if (selected == null) {
            if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
                throw new IllegalStateException("Cannot create a mirror pipeline while world settings require reload");
            }
            Iris.logger.info("Creating mirror pipeline for dimension {} and slot {}", realDimension, key.slot());
            selected = pipelineFactory.apply(realDimension);
            // A second pipeline for the same real dimension must not rebuild Embeddium terrain.
            if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
                WorldRenderingSettings.INSTANCE.clearReloadRequired();
            }
            mirror$mirrorPipelines.put(key, selected);
        }

        pipeline = selected;
        callback.setReturnValue(selected);
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"), require = 1, remap = false)
    private void mirror$destroyWithOculus(CallbackInfo callback) {
        mirror$destroyMirrorPipelines(false);
    }

    @Override
    public WorldRenderingPipeline mirror$getPipeline() {
        return pipeline;
    }

    @Override
    public void mirror$setPipeline(WorldRenderingPipeline value) {
        pipeline = value;
    }

    @Override
    public void mirror$clearMirrorPipelines() {
        mirror$destroyMirrorPipelines(true);
    }

    @Unique
    private void mirror$destroyMirrorPipelines(boolean invalidateTerrainPrograms) {
        if (mirror$mirrorPipelines.isEmpty()) return;
        if (mirror$mirrorPipelines.containsValue(pipeline)) {
            throw new IllegalStateException("Cannot destroy the active mirror pipeline");
        }
        mirror$mirrorPipelines.forEach((key, value) -> {
            Iris.logger.info("Destroying mirror pipeline for dimension {} and slot {}",
                    key.dimension(), key.slot());
            value.destroy();
        });
        mirror$mirrorPipelines.clear();
        if (invalidateTerrainPrograms) versionCounterForSodiumShaderReload++;
    }

    @Unique
    private record MirrorPipelineKey(NamespacedId dimension, MirrorPassContext.PipelineSlot slot) {
    }
}
