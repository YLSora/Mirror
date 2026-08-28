package com.mirror.mixin;

import com.google.common.collect.ImmutableSet;
import com.mirror.client.MirrorPassContext;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes Oculus center-depth autofocus operate on the physical mirror aperture, not capture
 * overscan, and prevents one mirror camera from smoothing against another mirror's center depth.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pathways.CenterDepthSampler", remap = false)
abstract class OculusCenterDepthSamplerMixin {
    @Unique
    private static final String MIRROR_CENTER_UNIFORM = "mirror_centerUv";
    @Unique
    private static final String MIRROR_RESET_UNIFORM = "mirror_resetHistory";

    @Shadow
    @Final
    private Program program;

    @Unique
    private int mirror$centerLocation = -1;
    @Unique
    private int mirror$resetLocation = -1;
    @Unique
    private boolean mirror$shaderPatched;
    @Unique
    private long mirror$lastMirrorViewId = Long.MIN_VALUE;

    /**
     * Avoid the multi-argument injector here. On Forge/Mixin 0.8.5 it introduces a generated helper
     * class which can become invisible to Oculus' module class loader. Redirecting the
     * builder invocation is equivalent for this use-case and does not create synthetic Args types.
     */
    @Redirect(method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/program/ProgramBuilder;begin(" +
                            "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;" +
                            "Lcom/google/common/collect/ImmutableSet;)" +
                            "Lnet/irisshaders/iris/gl/program/ProgramBuilder;"),
            require = 0, remap = false)
    private ProgramBuilder mirror$patchCenterDepthShader(String name,
                                                          String vertexSource,
                                                          String geometrySource,
                                                          String fragmentSource,
                                                          ImmutableSet<Integer> reservedTextureUnits) {
        String patchedSource = fragmentSource;
        String depthSample = "texture(depth, vec2(0.5))";
        String decayLine = "float decay2 = 1.0 - exp(-decay * lastFrameTime);";
        String declaration = "uniform float decay;";
        if (fragmentSource != null
                && fragmentSource.contains(depthSample)
                && fragmentSource.contains(decayLine)
                && fragmentSource.contains(declaration)) {
            patchedSource = fragmentSource
                    .replace(declaration, declaration + "\nuniform vec2 " + MIRROR_CENTER_UNIFORM
                            + ";\nuniform float " + MIRROR_RESET_UNIFORM + ";")
                    .replace(depthSample, "texture(depth, " + MIRROR_CENTER_UNIFORM + ")")
                    .replace(decayLine, decayLine + "\nif (" + MIRROR_RESET_UNIFORM
                            + " > 0.5) decay2 = 1.0;");
            mirror$shaderPatched = true;
        }

        // The center-depth shader is an Oculus implementation detail. If its source changed,
        // delegate the original source unchanged; the rest of the mirror compatibility remains
        // active instead of failing shader-pack initialization.
        return ProgramBuilder.begin(name, vertexSource, geometrySource, patchedSource, reservedTextureUnits);
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
    private void mirror$cacheMirrorUniforms(CallbackInfo callback) {
        if (!mirror$shaderPatched) return;
        int programId = program.getProgramId();
        mirror$centerLocation = GL20C.glGetUniformLocation(programId, MIRROR_CENTER_UNIFORM);
        mirror$resetLocation = GL20C.glGetUniformLocation(programId, MIRROR_RESET_UNIFORM);
        if (mirror$centerLocation < 0 || mirror$resetLocation < 0) {
            mirror$shaderPatched = false;
        }
    }

    @Inject(method = "sampleCenterDepth",
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pathways/FullScreenQuadRenderer;render()V",
                    shift = At.Shift.BEFORE),
            require = 0, remap = false)
    private void mirror$selectPhysicalMirrorCenter(CallbackInfo callback) {
        if (!mirror$shaderPatched) return;
        float centerU = 0.5f;
        float centerV = 0.5f;
        float resetHistory = 0.0f;
        if (MirrorPassContext.isActive()) {
            MirrorPassContext context = MirrorPassContext.current();
            centerU = context.reflectionCrop().centerU();
            centerV = context.reflectionCrop().centerV();
            // Oculus pipelines are shared per slot. Never smooth one reflected camera's center depth
            // against a different camera that happened to use the same slot immediately before it.
            // A single stable view still retains normal smoothing across consecutive frames.
            if (mirror$lastMirrorViewId != context.viewId()) {
                resetHistory = 1.0f;
                mirror$lastMirrorViewId = context.viewId();
            }
        }
        GL20C.glUniform2f(mirror$centerLocation, centerU, centerV);
        GL20C.glUniform1f(mirror$resetLocation, resetHistory);
    }
}
