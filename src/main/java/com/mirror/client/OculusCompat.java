package com.mirror.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraftforge.fml.ModList;

/** Optional boundary for an installed Oculus runtime. */
public final class OculusCompat {
    private static final boolean LOADED = ModList.get().isLoaded("oculus");
    private static final ThreadLocal<Integer> TRANSACTION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final Runtime RUNTIME = loadRuntime();

    private OculusCompat() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static void initialize() {
        if (LOADED) runtime().initialize();
    }

    public static boolean isMirrorPass() {
        return TRANSACTION_DEPTH.get() > 0;
    }

    public static boolean isShadowPass() {
        return LOADED && runtime().isShadowPass();
    }

    public static boolean shouldDeferSurfacePresentation() {
        return LOADED && !isMirrorPass() && runtime().isShaderPackInUse() && !runtime().isShadowPass();
    }

    static void beginMirrorTransaction() {
        TRANSACTION_DEPTH.set(TRANSACTION_DEPTH.get() + 1);
    }

    static void endMirrorTransaction() {
        int depth = TRANSACTION_DEPTH.get();
        if (depth <= 1) TRANSACTION_DEPTH.remove();
        else TRANSACTION_DEPTH.set(depth - 1);
    }

    public static void clearMirrorPipelines() {
        if (LOADED && RUNTIME != null) runtime().clearMirrorPipelines();
    }

    public static State capture(LevelRenderer renderer) {
        return LOADED ? new State(runtime().capture(renderer)) : State.disabled();
    }

    public static Object getLevelRendererPipeline(LevelRenderer renderer) {
        return runtime().getLevelRendererPipeline(renderer);
    }

    public static void setLevelRendererPipeline(LevelRenderer renderer, Object pipeline) {
        runtime().setLevelRendererPipeline(renderer, pipeline);
    }

    private static Runtime runtime() {
        if (RUNTIME == null) throw new IllegalStateException("Oculus runtime is unavailable");
        return RUNTIME;
    }

    private static Runtime loadRuntime() {
        if (!LOADED) return null;
        try {
            Class<?> type = Class.forName("com.mirror.client.OculusCompatImpl", true,
                    OculusCompat.class.getClassLoader());
            return (Runtime) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Cannot load the Oculus runtime", error);
        }
    }

    interface Runtime {
        void initialize();

        void clearMirrorPipelines();

        boolean isShaderPackInUse();

        boolean isShadowPass();

        Object getLevelRendererPipeline(LevelRenderer renderer);

        void setLevelRendererPipeline(LevelRenderer renderer, Object pipeline);

        Transaction capture(LevelRenderer renderer);
    }

    interface Transaction {
        void enterReflection();

        void close();
    }

    public static final class State implements AutoCloseable {
        private final Transaction transaction;

        private State(Transaction transaction) {
            this.transaction = transaction;
        }

        private static State disabled() {
            return new State(null);
        }

        public void enterReflection() {
            if (transaction != null) transaction.enterReflection();
        }

        @Override
        public void close() {
            if (transaction != null) transaction.close();
        }
    }
}
