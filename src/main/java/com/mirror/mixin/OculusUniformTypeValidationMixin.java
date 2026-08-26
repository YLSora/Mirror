package com.mirror.mixin;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.uniform.UniformType;
import org.lwjgl.opengl.ARBShaderImageLoadStore;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Rejects uniforms whose GLSL type cannot be updated by the uniform supplier.
 *
 * <p>Oculus 1.8 validates these types only after uniform locations have been
 * handed to custom uniforms. A rejected custom uniform can consequently keep
 * calling the wrong {@code glUniform*} function every composite pass. Validating
 * the location before it leaves the builder makes Oculus' existing "disable the
 * uniform" behavior effective for every uniform source.</p>
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramUniforms$Builder", remap = false)
abstract class OculusUniformTypeValidationMixin {
    @Shadow
    @Final
    private String name;

    @Shadow
    @Final
    private int program;

    @Unique
    private final Set<String> mirror$rejectedUniforms = new HashSet<>();

    @Inject(method = "location", at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void mirror$rejectWrongUniformType(String uniformName, UniformType provided,
                                                CallbackInfoReturnable<OptionalInt> callback) {
        int index = GL31C.glGetUniformIndices(program, uniformName);
        if (index == GL31C.GL_INVALID_INDEX) return;

        int glType = GL31C.glGetActiveUniformsi(program, index, GL31C.GL_UNIFORM_TYPE);
        UniformType expected = mirror$expectedType(glType);
        if (provided == expected) return;

        if (mirror$rejectedUniforms.add(uniformName)) {
            Iris.logger.error("[{}] Wrong uniform type for {}: Oculus is providing {} but the program "
                            + "expects {}. Disabling that uniform before its location is assigned.",
                    name, uniformName, provided, mirror$typeName(glType));
        }
        callback.setReturnValue(OptionalInt.empty());
    }

    @Unique
    private static UniformType mirror$expectedType(int type) {
        return switch (type) {
            case GL20C.GL_FLOAT -> UniformType.FLOAT;
            case GL20C.GL_INT, GL20C.GL_BOOL,
                    GL20C.GL_SAMPLER_1D, GL20C.GL_SAMPLER_2D, GL20C.GL_SAMPLER_3D,
                    GL20C.GL_SAMPLER_1D_SHADOW, GL20C.GL_SAMPLER_2D_SHADOW,
                    GL30C.GL_UNSIGNED_INT_SAMPLER_2D, GL30C.GL_UNSIGNED_INT_SAMPLER_3D -> UniformType.INT;
            case GL20C.GL_FLOAT_MAT3 -> UniformType.MAT3;
            case GL20C.GL_FLOAT_MAT4 -> UniformType.MAT4;
            case GL20C.GL_FLOAT_VEC2 -> UniformType.VEC2;
            case GL20C.GL_INT_VEC2 -> UniformType.VEC2I;
            case GL20C.GL_FLOAT_VEC3 -> UniformType.VEC3;
            case GL20C.GL_INT_VEC3 -> UniformType.VEC3I;
            case GL20C.GL_FLOAT_VEC4 -> UniformType.VEC4;
            case GL20C.GL_INT_VEC4 -> UniformType.VEC4I;
            default -> null;
        };
    }

    @Unique
    private static String mirror$typeName(int type) {
        if (type == ARBShaderImageLoadStore.GL_IMAGE_1D
                || type == ARBShaderImageLoadStore.GL_IMAGE_2D
                || type == ARBShaderImageLoadStore.GL_IMAGE_3D) {
            return "image";
        }
        UniformType expected = mirror$expectedType(type);
        return expected == null ? "unsupported GLSL type 0x" + Integer.toHexString(type) : expected.name();
    }
}
