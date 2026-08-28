package com.mirror.mixin;

import com.mirror.client.MirrorPassContext;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

/** Supplies previous matrices from the persistent mirror view rather than the shared pipeline. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.MatrixUniforms", remap = false)
abstract class OculusPreviousMatrixMixin {
    @Redirect(method = "addMatrix",
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/uniform/UniformHolder;uniformMatrix(" +
                            "Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;" +
                            "Ljava/lang/String;Ljava/util/function/Supplier;)" +
                            "Lnet/irisshaders/iris/gl/uniform/UniformHolder;"),
            require = 3, remap = false)
    private static UniformHolder mirror$registerPerViewPreviousMatrix(
            UniformHolder holder, UniformUpdateFrequency frequency,
            String name, Supplier<Matrix4f> supplier) {
        if ("gbufferPreviousModelView".equals(name)) {
            return holder.uniformMatrix(frequency, name,
                    () -> MirrorPassContext.isActive()
                            ? MirrorPassContext.current().previousModelView()
                            : supplier.get());
        }
        if ("gbufferPreviousProjection".equals(name)) {
            return holder.uniformMatrix(frequency, name,
                    () -> MirrorPassContext.isActive()
                            ? MirrorPassContext.current().previousProjection()
                            : supplier.get());
        }
        return holder.uniformMatrix(frequency, name, supplier);
    }
}
