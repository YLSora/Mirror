package com.mirror.compat.mixin;

import com.mirror.client.MirrorPassContext;
import com.mirror.compat.MirrorViewResources;
import com.mirror.compat.VoxyDepthTarget;
import com.mirror.compat.VoxyIrisBinding;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.Iris;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisVoxyRenderPipeline.class, remap = false)
abstract class VoxyIrisTargetsMixin implements VoxyIrisBinding, MirrorViewResources.Owner {
    @Shadow @Final @Mutable private IrisVoxyRenderPipelineData data;
    @Unique private IrisVoxyRenderPipelineData mirror$mainData;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void mirror$rememberMainData(CallbackInfo callback) {
        mirror$mainData = data;
    }

    @Override
    public void mirror$selectIrisBindings() {
        if (!MirrorPassContext.isActive()) return;
        var pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IGetIrisVoxyPipelineData access)
                || access.voxy$getPipelineData() == null) {
            throw new IllegalStateException("Active mirror shader pipeline has no Voxy bindings");
        }
        MirrorViewResources.register(this);
        mirror$select(access.voxy$getPipelineData(), MirrorPassContext.current().viewId());
    }

    @Unique
    private void mirror$select(IrisVoxyRenderPipelineData selected, long view) {
        IrisVoxyRenderPipeline self = (IrisVoxyRenderPipeline) (Object) this;
        if (selected != data) {
            if (selected.thePipeline != null && selected.thePipeline != self) {
                throw new IllegalStateException("Voxy shader bindings already have a renderer");
            }
            // Compiled Voxy shaders use this UBO layout; every secondary Iris pipeline must agree.
            var expected = mirror$mainData.getUniforms();
            var actual = selected.getUniforms();
            if ((expected == null) != (actual == null)
                    || (expected != null && !expected.layout().equals(actual.layout()))) {
                throw new IllegalStateException("Mirror Voxy uniform layout differs from the shared shader");
            }
            data.thePipeline = null;
            data = selected;
            data.thePipeline = self;
            mirror$bindColors(self.fb.framebuffer.id, data.opaqueDrawTargets);
            mirror$bindColors(self.fbTranslucent.framebuffer.id, data.translucentDrawTargets);
        }
        ((VoxyDepthTarget) self.fb).mirror$selectDepth(view);
        ((VoxyDepthTarget) self.fbTranslucent).mirror$selectDepth(view);
    }

    @Unique
    private static void mirror$bindColors(int framebuffer, int[] textures) {
        for (int i = 0; i < textures.length; i++) {
            GL45C.glNamedFramebufferTexture(framebuffer, GL30C.GL_COLOR_ATTACHMENT0 + i, textures[i], 0);
        }
    }

    @Override public void restoreMainView() { mirror$select(mirror$mainData, -1L); }
    @Override public void releaseMirrorView(long view) { }
    @Override public void clearMirrorViews() { restoreMainView(); }

    @Inject(method = "free", at = @At("HEAD"), require = 1)
    private void mirror$releaseBindings(CallbackInfo callback) {
        restoreMainView();
        MirrorViewResources.unregister(this);
    }
}
