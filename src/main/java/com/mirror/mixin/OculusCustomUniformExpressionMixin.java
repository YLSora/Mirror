package com.mirror.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Adds syntax aliases used by newer Iris custom-uniform expressions to Oculus 1.8's evaluator. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.custom.CustomUniforms$Builder", remap = false)
abstract class OculusCustomUniformExpressionMixin {
    @ModifyVariable(method = "addVariable", at = @At("HEAD"), argsOnly = true,
            index = 3, require = 1, remap = false)
    private String mirror$aliasFractToOculusFrac(String expression) {
        if (expression == null || expression.indexOf("fract") < 0) return expression;
        // Oculus 1.8 implements the OptiFine spelling "frac" with the same scalar semantics while
        // newer Iris shader packs also use the GLSL spelling "fract" in custom expressions.
        return expression.replaceAll("\\bfract(?=\\s*\\()", "frac");
    }
}
