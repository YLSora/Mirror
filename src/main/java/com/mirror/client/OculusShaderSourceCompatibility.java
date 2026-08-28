package com.mirror.client;

import java.util.regex.Pattern;

/**
 * Capability-based GLSL compatibility for shader interfaces present in newer Iris shader packs
 * but missing from Oculus 1.8's 1.20.1 transformation paths.
 *
 * <p>The patch is intentionally pack-agnostic. A declaration is injected only when the final GLSL
 * actually references the capability and no declaration/definition survived preprocessing.</p>
 */
public final class OculusShaderSourceCompatibility {
    private static final Pattern PROJ_MAD_CALL = Pattern.compile("\\bprojMAD\\s*\\(");
    private static final Pattern PROJ_MAD_DEFINITION = Pattern.compile(
            "(?:#\\s*define\\s+projMAD\\b|\\bvec3\\s+projMAD\\s*\\()", Pattern.MULTILINE);
    private static final Pattern DIAGONAL3_CALL = Pattern.compile("\\bdiagonal3\\s*\\(");
    private static final Pattern DIAGONAL3_DEFINITION = Pattern.compile(
            "(?:#\\s*define\\s+diagonal3\\b|\\bvec3\\s+diagonal3\\s*\\()", Pattern.MULTILINE);
    private static final Pattern SHADOW0_REFERENCE = Pattern.compile("\\bshadowtex0HW\\b");
    private static final Pattern SHADOW1_REFERENCE = Pattern.compile("\\bshadowtex1HW\\b");
    private static final Pattern SHADOW0_DECLARATION = Pattern.compile(
            "(?:\\buniform\\b[^;\\n]*\\bshadowtex0HW\\b|#\\s*define\\s+shadowtex0HW\\b)",
            Pattern.MULTILINE);
    private static final Pattern SHADOW1_DECLARATION = Pattern.compile(
            "(?:\\buniform\\b[^;\\n]*\\bshadowtex1HW\\b|#\\s*define\\s+shadowtex1HW\\b)",
            Pattern.MULTILINE);

    private OculusShaderSourceCompatibility() {
    }

    public static String patch(String source) {
        if (source == null || source.isEmpty()) return source;

        boolean needsProjMad = PROJ_MAD_CALL.matcher(source).find()
                && !PROJ_MAD_DEFINITION.matcher(source).find();
        boolean needsDiagonal3 = (DIAGONAL3_CALL.matcher(source).find() || needsProjMad)
                && !DIAGONAL3_DEFINITION.matcher(source).find();
        boolean needsShadow0 = SHADOW0_REFERENCE.matcher(source).find()
                && !SHADOW0_DECLARATION.matcher(source).find();
        boolean needsShadow1 = SHADOW1_REFERENCE.matcher(source).find()
                && !SHADOW1_DECLARATION.matcher(source).find();
        if (!needsProjMad && !needsDiagonal3 && !needsShadow0 && !needsShadow1) return source;

        StringBuilder compatibility = new StringBuilder(256);
        compatibility.append("\n// Mirror/Oculus 1.8 shader-interface compatibility\n");
        if (needsShadow0) compatibility.append("uniform sampler2DShadow shadowtex0HW;\n");
        if (needsShadow1) compatibility.append("uniform sampler2DShadow shadowtex1HW;\n");
        // Macros match the long-lived OptiFine/Iris helper idiom without constraining matrix types.
        if (needsDiagonal3) {
            compatibility.append("#define diagonal3(m) vec3((m)[0].x, (m)[1].y, (m)[2].z)\n");
        }
        if (needsProjMad) {
            compatibility.append("#define projMAD(m, v) (diagonal3(m) * (v) + (m)[3].xyz)\n");
        }
        compatibility.append('\n');

        int insertion = compatibilityInsertionPoint(source);
        MirrorDiagnostics.recordShaderSourceCompatibilityPatch();
        return source.substring(0, insertion) + compatibility + source.substring(insertion);
    }

    /** Keeps #version first and all leading #extension/#pragma directives ahead of injected GLSL. */
    private static int compatibilityInsertionPoint(String source) {
        int cursor = 0;
        int newline = source.indexOf('\n', cursor);
        if (newline < 0) return source.length();
        String first = source.substring(0, newline).trim();
        if (!first.startsWith("#version")) return 0;
        cursor = newline + 1;

        while (cursor < source.length()) {
            newline = source.indexOf('\n', cursor);
            int end = newline < 0 ? source.length() : newline;
            String line = source.substring(cursor, end).trim();
            if (!(line.startsWith("#extension") || line.startsWith("#pragma") || line.isEmpty())) break;
            cursor = newline < 0 ? source.length() : newline + 1;
        }
        return cursor;
    }
}
