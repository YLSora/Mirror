package com.mirror.client;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure GLSL reference scan for absolute custom images shared with the primary pipeline. */
public final class MirrorCustomImageSharing {
    private MirrorCustomImageSharing() {
    }

    public static boolean scansProgram(String group, String id) {
        return !"Shadow".equals(group) && !"ShadowComposite".equals(group)
                && !("Dh".equals(group) && "DhShadow".equals(id));
    }

    public record Source(String program, String text) {
        public Source {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(text, "text");
        }
    }

    public record Decision(boolean readOnly, String reason) {
    }

    public static Map<String, Decision> scan(Collection<Source> sources, Collection<String> imageNames) {
        Map<String, String> writeReasons = new LinkedHashMap<>();
        for (String name : imageNames) {
            if (!isIdentifier(name)) writeReasons.put(name, "not a GLSL identifier");
        }

        for (Source source : sources) {
            String code = stripComments(source.text());
            if (code.contains("##")) {
                for (String name : imageNames) {
                    writeReasons.putIfAbsent(name, source.program() + " uses macro token concatenation (##)");
                }
            }
            for (String name : imageNames) {
                if (writeReasons.containsKey(name)) continue;
                String reason = firstWrite(source.program(), code, name);
                if (reason != null) writeReasons.put(name, reason);
            }
        }

        Map<String, Decision> decisions = new LinkedHashMap<>();
        for (String name : imageNames) {
            String reason = writeReasons.get(name);
            decisions.put(name, reason == null
                    ? new Decision(true, "no writes found")
                    : new Decision(false, reason));
        }
        return Collections.unmodifiableMap(decisions);
    }

    public static String borrowFailure(boolean enabled, boolean absoluteImage, boolean primaryIsIris,
                                       boolean safetyAvailable, boolean sameShaderPack,
                                       boolean primaryImageAvailable, boolean sourcesAvailable, boolean readOnly) {
        if (!enabled) return "disabled by config";
        if (!absoluteImage) return "relative image";
        if (!primaryIsIris) return "primary pipeline is not IrisRenderingPipeline";
        if (!safetyAvailable) return "custom-image safety mixin unavailable";
        if (!sameShaderPack) return "shader pack mismatch";
        if (!primaryImageAvailable) return "primary image unavailable";
        if (!sourcesAvailable) return "external renderer shader sources unavailable";
        if (!readOnly) return "shader writes image";
        return null;
    }

    private static String firstWrite(String program, String code, String name) {
        Pattern tokenPattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
        Matcher tokens = tokenPattern.matcher(code);
        while (tokens.find()) {
            int start = tokens.start();
            if (isPreprocessorLine(code, start)) {
                return program + " uses macro/preprocessor reference: " + compact(lineAt(code, start));
            }
            if (isUniformDeclaration(code, name, start) || isReadOnlyBuiltin(code, name, start, "imageLoad")
                    || isReadOnlyBuiltin(code, name, start, "imageSize")) continue;

            String line = lineAt(code, start);
            Matcher writer = Pattern.compile("\\b(imageStore|imageAtomic[A-Za-z0-9_]*)\\s*\\(\\s*"
                    + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(line);
            if (writer.find()) return program + " uses " + writer.group(1) + ": " + compact(line);
            return program + " uses other image reference: " + compact(line);
        }
        return null;
    }

    private static boolean isUniformDeclaration(String code, String name, int position) {
        Pattern declaration = Pattern.compile("(?:^|[;{}])\\s*(?:layout\\s*\\([^;{}]*\\)\\s*)?\\buniform\\b"
                + "[^;{}]*?\\b" + Pattern.quote(name) + "\\b\\s*(?:\\[[^]]*])?\\s*;",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher matcher = declaration.matcher(code);
        while (matcher.find()) {
            if (position >= matcher.start() && position < matcher.end()) return true;
        }
        return false;
    }

    private static boolean isReadOnlyBuiltin(String code, String name, int position, String function) {
        Pattern call = Pattern.compile("\\b" + function + "\\s*\\(\\s*" + Pattern.quote(name)
                + "(?![A-Za-z0-9_])\\s*(?=,|\\))");
        Matcher matcher = call.matcher(code);
        while (matcher.find()) {
            if (position >= matcher.start() && position < matcher.end()) return true;
        }
        return false;
    }

    private static boolean isPreprocessorLine(String code, int position) {
        int lineStart = code.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        int marker = code.indexOf('#', lineStart);
        return marker >= 0 && marker < position && code.substring(lineStart, marker).isBlank();
    }

    private static String lineAt(String code, int position) {
        int start = code.lastIndexOf('\n', Math.max(0, position - 1)) + 1;
        int end = code.indexOf('\n', position);
        return code.substring(start, end < 0 ? code.length() : end).trim();
    }

    private static String compact(String text) {
        String line = text.replaceAll("\\s+", " ").trim();
        return line.length() <= 120 ? line : line.substring(0, 117) + "...";
    }

    private static boolean isIdentifier(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static String stripComments(String source) {
        StringBuilder result = new StringBuilder(source);
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < result.length(); i++) {
            char current = result.charAt(i);
            char next = i + 1 < result.length() ? result.charAt(i + 1) : 0;
            if (lineComment) {
                if (current == '\n') lineComment = false;
                else result.setCharAt(i, ' ');
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    result.setCharAt(i, ' ');
                    result.setCharAt(i + 1, ' ');
                    i++;
                    blockComment = false;
                } else if (current != '\n' && current != '\r') {
                    result.setCharAt(i, ' ');
                }
            } else if (quote != 0) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == quote) quote = 0;
            } else if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '/' && next == '/') {
                result.setCharAt(i, ' ');
                result.setCharAt(i + 1, ' ');
                i++;
                lineComment = true;
            } else if (current == '/' && next == '*') {
                result.setCharAt(i, ' ');
                result.setCharAt(i + 1, ' ');
                i++;
                blockComment = true;
            }
        }
        return result.toString();
    }
}