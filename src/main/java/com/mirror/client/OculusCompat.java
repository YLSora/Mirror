package com.mirror.client;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional Oculus/Iris state isolation.  No Oculus type is referenced in this class, so the
 * client can load normally when shaders are not installed.  Oculus changes package-private
 * pipeline state between releases; the small reflective adapter deliberately snapshots only
 * mutable pipeline/frame/shadow/captured-state fields and restores them in reverse order.
 */
public final class OculusCompat {
    private static final boolean LOADED = ModList.get().isLoaded("oculus")
            || ModList.get().isLoaded("iris");
    private static final String[] PIPELINE_CLASSES = {
            "net.irisshaders.iris.Iris",
            "net.irisshaders.iris.pipeline.PipelineManager",
            "net.irisshaders.iris.pipeline.IrisRenderingPipeline",
            "net.irisshaders.iris.shadows.ShadowRenderer",
            "net.coderbot.iris.Iris",
            "net.coderbot.iris.pipeline.PipelineManager",
            "net.coderbot.iris.shadows.ShadowRenderer"
    };
    private static volatile boolean mirrorPass;

    private OculusCompat() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static void beginPipelineHook() {
        mirrorPass = true;
    }

    public static void endPipelineHook() {
        mirrorPass = false;
    }

    public static State capture() {
        if (!LOADED) return State.EMPTY;
        Map<Field, Object> values = new HashMap<>();
        List<FieldValue> result = new ArrayList<>();
        for (String className : PIPELINE_CLASSES) {
            Class<?> type = load(className);
            if (type == null) continue;
            collectStatic(type, values, result);
        }
        // A pipeline manager usually stores the active pipeline in a static field.  Include one
        // level of its mutable state so captured rendering state and target version counters do
        // not leak even when those fields are not static themselves.
        for (FieldValue value : List.copyOf(result)) {
            if (value.value() == null || !isPipelineObject(value.value().getClass())) continue;
            collectInstance(value.value(), values, result);
        }
        return result.isEmpty() ? State.EMPTY : new State(result);
    }

    private static void collectStatic(Class<?> type, Map<Field, Object> seen, List<FieldValue> result) {
        for (Field field : allFields(type)) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || !isStateField(field)) continue;
            add(field, null, seen, result);
        }
    }

    private static void collectInstance(Object owner, Map<Field, Object> seen, List<FieldValue> result) {
        for (Field field : allFields(owner.getClass())) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || !isStateField(field)) continue;
            add(field, owner, seen, result);
        }
    }

    private static void add(Field field, Object owner, Map<Field, Object> seen, List<FieldValue> result) {
        if (seen.putIfAbsent(field, owner) != null) return;
        try {
            field.setAccessible(true);
            result.add(new FieldValue(field, owner, field.get(owner)));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A changed Oculus access transformer must disable only this optional snapshot entry,
            // never the mirror or the client.
        }
    }

    private static boolean isStateField(Field field) {
        String name = field.getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("pipeline") || name.contains("captur") || name.contains("shadow")
                || name.contains("target") || name.contains("version") || name.contains("frame")
                || name.contains("rendering") || name.contains("gametime") || name.contains("time");
    }

    private static boolean isPipelineObject(Class<?> type) {
        String name = type.getName().toLowerCase(java.util.Locale.ROOT);
        return name.contains("pipeline") || name.contains("renderingstate")
                || name.contains("capturedstate") || name.contains("shadowrenderer");
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) fields.add(field);
        }
        return fields;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, OculusCompat.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    public static final class State implements AutoCloseable {
        private static final State EMPTY = new State(List.of());
        private final List<FieldValue> values;
        private boolean restored;

        private State(List<FieldValue> values) {
            this.values = List.copyOf(values);
        }

        public void enterReflection() {
            if (this == EMPTY) return;
            mirrorPass = true;
            for (FieldValue value : values) {
                if (value.field().getType() != boolean.class) continue;
                String name = value.field().getName().toLowerCase(java.util.Locale.ROOT);
                if (name.equals("active") || name.contains("renderingshadow")
                        || name.contains("shadowpass") || name.contains("shadowrendering")) {
                    try {
                        value.field().set(value.owner(), false);
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Optional state; the regular snapshot restore still runs.
                    }
                }
            }
        }

        @Override
        public void close() {
            if (restored) return;
            restored = true;
            for (int i = values.size() - 1; i >= 0; i--) values.get(i).restore();
            mirrorPass = false;
        }
    }

    private record FieldValue(Field field, Object owner, Object value) {
        private void restore() {
            try {
                field.set(owner, value);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Oculus is optional and its private state is version-specific.
            }
        }
    }
}
