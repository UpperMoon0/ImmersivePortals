package qouteall.imm_ptl.core.render;

import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clipping for the final vertex-producing stage of Iris world programs.
 *
 * Iris has different inputs for terrain, entities and particles. Recovering view
 * position from the final clip-space position works for all of them, including
 * shader displacement, geometry output and tessellation evaluation. The plane is
 * FrontClipping's AFTER-model-view equation, not the camera-relative equation.
 * This class deliberately has no Iris dependencies so source regressions can run
 * without loading an optional renderer (or a GL context).
 */
public final class ShaderClippingTransformation {
    public enum Stage { VERTEX, GEOMETRY, TESS_EVAL }

    private static final String PREFIX = "immptl_";
    private static final String MAIN = PREFIX + "unclippedMain";
    private static final String DISTANCE = PREFIX + "clipDistance";
    private static final Pattern MAIN_DECLARATION = Pattern.compile(
        "\\bvoid\\s+(main)\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{"
    );
    private static final Pattern EMIT = Pattern.compile("\\b(?:EmitVertex|EmitStreamVertex)\\s*\\(");
    private static final Set<String> TERRAIN_PROGRAMS = Set.of(
        "gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_terrain", "gbuffers_water",
        // These are the complete ProgramId terrain/water fallback chain.
        "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"
    );
    private static final Set<String> WORLD_PROGRAMS = Set.of(
        // ShaderCreator.patchVanilla receives ShaderKey.getName(), not ProgramId/source.getName().
        "basic", "basic_color", "textured", "textured_color", "sps",
        "terrain_solid", "terrain_cutout", "terrain_translucent", "moving_block",
        "entities_alpha", "entities_solid", "entities_solid_diffuse", "entities_solid_bright",
        "entities_cutout", "entities_cutout_diffuse", "entities_translucent", "entities_eyes",
        "entities_eyes_trans", "lightning", "leash", "text_bg", "particles", "particles_trans",
        "weather", "crumbling", "text", "text_intensity", "text_be", "text_intensity_be",
        "block_entity", "block_entity_bright", "block_entity_diffuse", "be_translucent",
        "beacon", "glint", "lines", "ie_compat", "mekanism_flame"
    );

    private ShaderClippingTransformation() {}

    public static boolean isWorldProgram(String patch, String name) {
        return switch (patch) {
            case "SODIUM", "EMBEDDIUM" -> TERRAIN_PROGRAMS.contains(name);
            case "VANILLA" -> WORLD_PROGRAMS.contains(name);
            default -> false;
        };
    }

    /** Return a copy only for eligible world programs, leaving Iris's name-independent cache intact. */
    public static <S extends Enum<S>> Map<S, String> transformProgram(
        String patch, String name, Map<S, String> sources
    ) {
        if (sources == null || !isWorldProgram(patch, name)) return sources;
        for (Stage stage : List.of(Stage.GEOMETRY, Stage.TESS_EVAL, Stage.VERTEX)) {
            for (Map.Entry<S, String> entry : sources.entrySet()) {
                if (entry.getKey().name().equals(stage.name()) && entry.getValue() != null) {
                    Map<S, String> result = new LinkedHashMap<>(sources);
                    result.put(entry.getKey(), transform(entry.getValue(), stage,
                        patch.equals("VANILLA") ? "iris_ProjMat" : "iris_ProjectionMatrix"));
                    return result;
                }
            }
        }
        return sources;
    }

    public static String transform(String source, Stage stage, String projectionUniform) {
        String masked = maskComments(source);
        // Do not patch twice after reload, or rewrite names inside comments.
        if (masked.contains(MAIN) || masked.contains(DISTANCE)) {
            return source;
        }
        Matcher main = MAIN_DECLARATION.matcher(masked);
        if (!main.find()) {
            throw new IllegalArgumentException("World shader has no main function for portal clipping");
        }

        String declarations = "\nuniform vec4 iportal_ClippingEquation;\n";
        if (!Pattern.compile("\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?mat4\\s+"
            + Pattern.quote(projectionUniform) + "\\b").matcher(masked).find()) {
            declarations += "uniform mat4 " + projectionUniform + ";\n";
        }
        String distanceFunction = "float " + DISTANCE + "(vec4 position) {\n"
            // A neutral plane must be positive even for unusual pack clip-space w values.
            + "    if (all(equal(iportal_ClippingEquation, vec4(0.0, 0.0, 0.0, 1.0)))) return 1.0;\n"
            + "    return dot(inverse(" + projectionUniform + ") * position, iportal_ClippingEquation);\n"
            + "}\n";
        String result;
        if (stage == Stage.GEOMETRY) {
            result = rewriteEmissionStatements(source, masked);
            int insertion = initialDirectivesEnd(maskComments(result));
            result = result.substring(0, insertion) + "\nfloat " + DISTANCE + "(vec4 position);\n"
                + result.substring(insertion) + declarations + distanceFunction;
        }
        else {
            // A wrapper runs after early returns, and does not assume main is the last function.
            result = source.substring(0, main.start(1)) + MAIN + source.substring(main.end(1))
                + declarations + distanceFunction
                + "void main() {\n    " + MAIN + "();\n"
                + "    gl_ClipDistance[0] = " + DISTANCE + "(gl_Position);\n}\n";
        }
        return ensureOutputClipDistance(result);
    }

    private static String rewriteEmissionStatements(String source, String masked) {
        Matcher matcher = EMIT.matcher(masked);
        StringBuilder result = new StringBuilder();
        int copiedUntil = 0;
        while (matcher.find()) {
            int end = matcher.end();
            int depth = 1;
            while (end < masked.length() && depth > 0) {
                char c = masked.charAt(end++);
                if (c == '(') depth++;
                if (c == ')') depth--;
            }
            while (end < masked.length() && Character.isWhitespace(masked.charAt(end))) end++;
            if (depth != 0 || end == masked.length() || masked.charAt(end) != ';') {
                throw new IllegalArgumentException("Unrecognized geometry emission statement");
            }
            end++;
            result.append(source, copiedUntil, matcher.start());
            // Preserve EmitStreamVertex's compile-time constant stream argument and if/else scopes.
            result.append("{ gl_ClipDistance[0] = ").append(DISTANCE).append("(gl_Position); ")
                .append(source, matcher.start(), end).append(" }");
            copiedUntil = end;
        }
        return result.append(source, copiedUntil, source.length()).toString();
    }

    private static String ensureOutputClipDistance(String source) {
        String masked = maskComments(source);
        Matcher block = Pattern.compile("\\bout\\s+gl_PerVertex\\s*\\{").matcher(masked);
        if (!block.find()) return source;
        int end = masked.indexOf('}', block.end());
        if (end < 0) throw new IllegalArgumentException("Unclosed gl_PerVertex output block");
        if (masked.substring(block.end(), end).contains("gl_ClipDistance")) return source;
        return source.substring(0, end) + "\nfloat gl_ClipDistance[1];\n" + source.substring(end);
    }

    private static int initialDirectivesEnd(String masked) {
        int end = 0;
        while (end < masked.length()) {
            int newline = masked.indexOf('\n', end);
            int lineEnd = newline < 0 ? masked.length() : newline + 1;
            String line = masked.substring(end, lineEnd).strip();
            if (!line.isEmpty() && !line.startsWith("#")) break;
            end = lineEnd;
        }
        return end;
    }

    /** Replace comments with same-length whitespace, preserving source offsets and line numbers. */
    static String maskComments(String source) {
        char[] chars = source.toCharArray();
        boolean line = false;
        boolean block = false;
        for (int i = 0; i < chars.length; i++) {
            if (line && chars[i] == '\n') line = false;
            if (block && chars[i] == '*' && i + 1 < chars.length && chars[i + 1] == '/') {
                chars[i++] = ' ';
                chars[i] = ' ';
                block = false;
                continue;
            }
            if (!line && !block && chars[i] == '/' && i + 1 < chars.length) {
                if (chars[i + 1] == '/') line = true;
                else if (chars[i + 1] == '*') block = true;
                if (line || block) {
                    chars[i++] = ' ';
                    chars[i] = ' ';
                    continue;
                }
            }
            if ((line || block) && chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
        }
        return new String(chars);
    }
}
