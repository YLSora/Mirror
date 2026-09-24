package com.mirror.compat.mixin;

import com.mirror.client.MirrorPassContext;
import com.mirror.compat.ThirdPartyFrame;
import com.mirror.compat.VoxyIrisHistoryAccess;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.iris.VoxyUniforms;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

@Mixin(value = VoxyUniforms.class, remap = false)
abstract class VoxyIrisPreviousUniformMixin {
    @Redirect(method = "addUniforms", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/uniform/UniformHolder;uniformMatrix("
                    + "Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;"
                    + "Ljava/lang/String;Ljava/util/function/Supplier;)"
                    + "Lnet/irisshaders/iris/gl/uniform/UniformHolder;"), require = 9)
    private static UniformHolder mirror$perViewportHistory(UniformHolder holder,
            UniformUpdateFrequency frequency, String name, Supplier<Matrix4f> original) {
        if (!name.equals("vxProjPrev") && !name.equals("vxModelViewPrev") && !name.equals("vxViewProjPrev")) {
            return holder.uniformMatrix(frequency, name, original);
        }
        return holder.uniformMatrix(frequency, name, () -> {
            if (!MirrorPassContext.isActive() || ThirdPartyFrame.auxiliary()) return original.get();
            var renderer = IGetVoxyRenderSystem.getNullable();
            if (renderer == null) return new Matrix4f();
            var viewport = renderer.getViewport();
            if (viewport == null) return new Matrix4f();
            VoxyIrisHistoryAccess history = (VoxyIrisHistoryAccess) viewport;
            return switch (name) {
                case "vxProjPrev" -> history.mirror$previousProjection();
                case "vxModelViewPrev" -> history.mirror$previousModelView();
                case "vxViewProjPrev" -> history.mirror$previousViewProjection();
                default -> throw new IllegalStateException(name);
            };
        });
    }
}
