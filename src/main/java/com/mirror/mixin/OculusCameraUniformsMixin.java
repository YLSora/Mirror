package com.mirror.mixin;

import com.mirror.client.MirrorPassContext;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

/** Makes Oculus near/far uniforms describe the projection actually used by a mirror pass. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms", remap = false)
abstract class OculusCameraUniformsMixin {
    /**
     * Redirect rather than ModifyArgs here. Forge 1.20.1 ships Mixin 0.8.5 under ModLauncher;
     * That injector requires a generated argument-helper class which is not reliably visible from
     * Oculus' module class loader in the Forge userdev/runtime layout.
     * A direct invoke redirect has no generated synthetic class and preserves all non-mirror calls.
     */
    @Redirect(method = "addCameraUniforms",
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/uniform/UniformHolder;uniform1f(" +
                            "Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;" +
                            "Ljava/lang/String;Ljava/util/function/DoubleSupplier;)" +
                            "Lnet/irisshaders/iris/gl/uniform/UniformHolder;"),
            require = 1, remap = false)
    private static UniformHolder mirror$redirectDoubleUniform(UniformHolder holder,
                                                               UniformUpdateFrequency frequency,
                                                               String name,
                                                               DoubleSupplier supplier) {
        if (!"near".equals(name)) {
            return holder.uniform1f(frequency, name, supplier);
        }

        // Oculus normally registers near as ONCE because vanilla always uses 0.05. Mirror distance
        // changes per capture, and mirror pipelines can be pre-created before a mirror pass exists,
        // so install one dynamic supplier for every pipeline and branch only when it is evaluated.
        return holder.uniform1f(UniformUpdateFrequency.PER_FRAME, name,
                () -> MirrorPassContext.isActive()
                        ? MirrorPassContext.current().nearPlane()
                        : supplier.getAsDouble());
    }

    @Redirect(method = "addCameraUniforms",
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/uniform/UniformHolder;uniform1f(" +
                            "Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;" +
                            "Ljava/lang/String;Ljava/util/function/IntSupplier;)" +
                            "Lnet/irisshaders/iris/gl/uniform/UniformHolder;"),
            require = 1, remap = false)
    private static UniformHolder mirror$redirectIntUniform(UniformHolder holder,
                                                            UniformUpdateFrequency frequency,
                                                            String name,
                                                            IntSupplier supplier) {
        if (!"far".equals(name)) {
            return holder.uniform1f(frequency, name, supplier);
        }

        return holder.uniform1f(frequency, name,
                () -> MirrorPassContext.isActive()
                        ? Math.max(1, Math.round(MirrorPassContext.current().renderDistance()))
                        : supplier.getAsInt());
    }
}
