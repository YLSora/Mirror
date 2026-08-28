package com.mirror.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Program;
import net.minecraftforge.fml.ModList;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;

/**
 * Restores a stable shader-compilation failure path around Oculus 1.8.0's Program mixin.
 *
 * <p>That Oculus version attempts to cancel {@code Program.compileShaderInternal} after a failed
 * compile even though its callback is not cancellable. The resulting Mixin
 * {@code CancellationException} hides the actual GLSL compiler log and is then reported once for
 * every pipeline construction. Detect the failure immediately after OpenGL compilation and use the
 * vanilla checked-exception path before the broken callback can run.</p>
 */
@Mixin(Program.class)
abstract class OculusShaderCompileGuardMixin {
    @Redirect(method = "compileShaderInternal",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;glCompileShader(I)V"),
            require = 1)
    private static void mirror$compileShaderWithStableFailure(int shader) throws IOException {
        GlStateManager.glCompileShader(shader);
        if (!ModList.get().isLoaded("oculus")) return;
        if (GlStateManager.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != 0) return;

        String log = GlStateManager.glGetShaderInfoLog(shader, 32768).trim();
        if (log.isEmpty()) log = "OpenGL reported shader compilation failure without an info log";
        throw new IOException("Couldn't compile shader: " + log);
    }
}
