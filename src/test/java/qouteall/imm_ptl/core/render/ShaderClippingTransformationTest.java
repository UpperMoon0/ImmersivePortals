package qouteall.imm_ptl.core.render;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Executable source-transformation regressions; these do not replace in-game pixel acceptance. */
class ShaderClippingTransformationTest {
    private enum Stage { VERTEX, TESS_CONTROL, TESS_EVAL, GEOMETRY, FRAGMENT, COMPUTE }

    private static final String VERTEX = """
        #version 330 core
        uniform mat4 iris_ProjectionMatrix;
        void main(void) {
            gl_Position = iris_ProjectionMatrix * vec4(1.0, 2.0, 3.0, 1.0);
            if (gl_Position.x < 0.0) return;
        }
        float functionAfterMain() { return 2.0; }
        """;

    @Test
    void resolvedTerrainProgramsIncludeEveryFallbackAndBothAdapters() {
        for (String patch : new String[]{"SODIUM", "EMBEDDIUM"}) {
            for (String name : new String[]{"terrain", "terrain_solid", "terrain_cutout", "water",
                "textured_lit", "textured", "basic"}) {
                assertTrue(ShaderClippingTransformation.isWorldProgram(patch, "gbuffers_" + name), name);
            }
        }
        for (String name : new String[]{"entities_solid", "entities_translucent", "block_entity",
            "be_translucent", "particles", "particles_trans", "moving_block", "text_be"}) {
            assertTrue(ShaderClippingTransformation.isWorldProgram("VANILLA", name), name);
        }
    }

    @Test
    void shadowSkyHandCompositeAndComputeProgramsAreNeverSelected() {
        for (String patch : new String[]{"SODIUM", "EMBEDDIUM", "VANILLA", "COMPOSITE", "COMPUTE", "DH_TERRAIN"}) {
            for (String name : new String[]{"shadow", "shadow_solid", "shadow_entities_cutout", "ie_compat_shadow",
                "sky_basic", "sky_textured", "clouds", "hand_cutout", "final", "composite", "deferred", "setup"}) {
                assertFalse(ShaderClippingTransformation.isWorldProgram(patch, name), patch + ":" + name);
            }
        }
        assertFalse(ShaderClippingTransformation.isWorldProgram("COMPOSITE", "gbuffers_terrain"));
        assertFalse(ShaderClippingTransformation.isWorldProgram("SODIUM", "gbuffers_entities"));
        assertFalse(ShaderClippingTransformation.isWorldProgram("VANILLA", "gbuffers_terrain"));
    }

    @Test
    void mainWrapperUsesFinalPositionAndSurvivesEarlyReturnAndTrailingFunctions() {
        String output = ShaderClippingTransformation.transform(VERTEX,
            ShaderClippingTransformation.Stage.VERTEX, "iris_ProjectionMatrix");
        assertTrue(output.contains("void immptl_unclippedMain(void)"));
        assertTrue(output.contains("if (gl_Position.x < 0.0) return;"));
        assertTrue(output.indexOf("float functionAfterMain()") < output.lastIndexOf("void main()"));
        assertTrue(output.contains("inverse(iris_ProjectionMatrix) * position"));
        assertTrue(output.contains("gl_ClipDistance[0] = immptl_clipDistance(gl_Position)"));
        assertFalse(output.contains("getVertexPosition"));
        assertFalse(output.contains("iris_ModelViewMatrix"));
        assertEquals(1, occurrences(output, "uniform mat4 iris_ProjectionMatrix;"));
        assertEquals(output, ShaderClippingTransformation.transform(output,
            ShaderClippingTransformation.Stage.VERTEX, "iris_ProjectionMatrix"));
    }

    @Test
    void commentsDoNotMasqueradeAsMainOrEmissionOrUniformDeclarations() {
        String source = """
            #version 330 core
            // void main() { EmitVertex(); }
            /* uniform mat4 iris_ProjMat; main() {} */
            void main() { gl_Position = vec4(1.0); }
            """;
        String output = ShaderClippingTransformation.transform(source,
            ShaderClippingTransformation.Stage.VERTEX, "iris_ProjMat");
        assertTrue(output.contains("// void main() { EmitVertex(); }"));
        assertTrue(output.contains("void immptl_unclippedMain() { gl_Position"));
        assertEquals(1, occurrences(ShaderClippingTransformation.maskComments(output), "uniform mat4 iris_ProjMat;"));
    }

    @Test
    void finalStageSelectionCopiesCacheAndDoesNotModifyEarlierStages() {
        Map<Stage, String> original = new EnumMap<>(Stage.class);
        original.put(Stage.VERTEX, VERTEX);
        original.put(Stage.TESS_CONTROL, "control unchanged");
        original.put(Stage.TESS_EVAL, VERTEX);
        original.put(Stage.FRAGMENT, "fragment unchanged");
        Map<Stage, String> tess = ShaderClippingTransformation.transformProgram("SODIUM", "gbuffers_terrain_solid", original);
        assertNotSame(original, tess);
        assertEquals(VERTEX, original.get(Stage.TESS_EVAL));
        assertEquals(VERTEX, tess.get(Stage.VERTEX));
        assertTrue(tess.get(Stage.TESS_EVAL).contains("gl_ClipDistance[0]"));
        assertEquals("control unchanged", tess.get(Stage.TESS_CONTROL));
        assertEquals("fragment unchanged", tess.get(Stage.FRAGMENT));
        // A later shadow transform sharing the same Iris cached source is untouched.
        assertSame(original, ShaderClippingTransformation.transformProgram("SODIUM", "shadow", original));
        original.put(Stage.GEOMETRY, "#version 330 core\nvoid main(){ gl_Position=vec4(1.0); EmitVertex(); }");
        Map<Stage, String> geometry = ShaderClippingTransformation.transformProgram("SODIUM", "gbuffers_water", original);
        assertEquals(VERTEX, geometry.get(Stage.TESS_EVAL));
        assertTrue(geometry.get(Stage.GEOMETRY).contains("gl_ClipDistance[0]"));
        original.put(Stage.GEOMETRY, null);
        assertTrue(ShaderClippingTransformation.transformProgram("VANILLA", "block_entity", original)
            .get(Stage.TESS_EVAL).contains("inverse(iris_ProjMat)"));
    }

    @Test
    void everyGeometryEmissionIsClippedBeforeEmissionWithoutBreakingConditionalScopes() {
        String source = """
            #version 400 core
            #extension GL_ARB_gpu_shader5 : enable
            layout(points) in;
            layout(points, max_vertices=3) out;
            out gl_PerVertex { vec4 gl_Position; };
            void main() {
                gl_Position = gl_in[0].gl_Position;
                if (gl_Position.x > 0.0) EmitVertex(); else EmitStreamVertex(0);
                for (int i=0; i<1; i++) EmitStreamVertex((0));
            }
            """;
        String output = ShaderClippingTransformation.transform(source,
            ShaderClippingTransformation.Stage.GEOMETRY, "iris_ProjMat");
        assertEquals(3, occurrences(output, "gl_ClipDistance[0] = immptl_clipDistance(gl_Position);"));
        assertTrue(output.contains("EmitVertex(); } else { gl_ClipDistance"));
        assertTrue(output.contains("EmitStreamVertex((0)); }"));
        assertTrue(output.contains("float gl_ClipDistance[1];"));
        assertTrue(output.indexOf("#extension") < output.indexOf("float immptl_clipDistance(vec4 position);"));
        assertEquals(output, ShaderClippingTransformation.transform(output,
            ShaderClippingTransformation.Stage.GEOMETRY, "iris_ProjMat"));
    }

    @Test
    void neutralPlaneReturnsPositiveConstantAndMalformedShaderFailsClearly() {
        String output = ShaderClippingTransformation.transform(VERTEX,
            ShaderClippingTransformation.Stage.VERTEX, "iris_ProjectionMatrix");
        assertTrue(output.contains("vec4(0.0, 0.0, 0.0, 1.0)))) return 1.0;"));
        assertThrows(IllegalArgumentException.class, () -> ShaderClippingTransformation.transform(
            "#version 330 core\n// void main() {}", ShaderClippingTransformation.Stage.VERTEX, "iris_ProjMat"));
        assertNull(ShaderClippingTransformation.transformProgram("VANILLA", "particles", null));
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
