package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.shaders.Program;
import me.shedaniel.cloth.clothconfig.shadowed.org.yaml.snakeyaml.Yaml;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CrumblingClippingRegressionTest {
    @Test
    void actualConfigurationTransformsDamageVerticesInCameraRelativeCoordinates() throws Exception {
        var field = ShaderCodeTransformation.class.getDeclaredField("configs");
        field.setAccessible(true);
        Object previous = field.get(null);
        try (var input = getClass().getResourceAsStream("/assets/immersive_portals/shaders/shader_transformation.yaml")) {
            assertNotNull(input);
            var configs = new Yaml().loadAs(new String(input.readAllBytes(), StandardCharsets.UTF_8),
                ShaderCodeTransformation.ConfigsObj.class);
            field.set(null, configs.configs);
            String original = "#version 150\nin vec3 Position; uniform mat4 ModelViewMat; uniform mat4 ProjMat;\n"
                + "void main() { gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0); }";
            String result = ShaderCodeTransformation.transform(Program.Type.VERTEX, "rendertype_crumbling", original);
            assertTrue(ShaderCodeTransformation.shouldAddUniform("rendertype_crumbling"));
            assertTrue(result.contains("uniform vec4 iportal_ClippingEquation;"));
            assertTrue(result.contains("dot(Position.xyz, iportal_ClippingEquation.xyz)"));
            assertFalse(result.contains("ChunkOffset"), "damage vertices are not chunk-local terrain vertices");
            assertEquals(original, ShaderCodeTransformation.transform(Program.Type.FRAGMENT, "rendertype_crumbling", original));
        }
        finally {
            field.set(null, previous);
        }
    }

    @Test
    void lateDrawScopeUploadsTheBeforeModelViewPlaneAndRestoresStateEvenOnFailure() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/qouteall/imm_ptl/core/mixin/client/render/shader/MixinVertexBuffer_VanillaClipping.class")) {
            assertNotNull(input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            var method = node.methods.stream().filter(m -> m.name.equals("ip_clipVanillaWorldDraw")).findFirst().orElseThrow();
            boolean setup = false, before = false, floatUpload = false, restore = false, skipsIrisOwnedShader = false;
            int index = 0, setupIndex = -1, uploadIndex = -1, drawIndex = -1;
            for (var instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                    && type.desc.equals("qouteall/imm_ptl/core/compat/iris_compatibility/IEIrisClippingShader")) {
                    skipsIrisOwnedShader = true;
                }
                if (instruction instanceof MethodInsnNode call) {
                    setup |= call.name.equals("setupInnerClipping");
                    if (call.name.equals("setupInnerClipping")) setupIndex = index;
                    if (call.name.equals("call") && uploadIndex >= 0 && drawIndex < 0) drawIndex = index;
                    before |= call.name.equals("getActiveClipPlaneEquationBeforeModelView");
                    restore |= call.name.equals("restoreClippingState");
                    if (call.owner.equals("com/mojang/blaze3d/shaders/Uniform") && call.name.equals("set")) {
                        assertEquals("(FFFF)V", call.desc, "the vec4 float uniform must never select the integer overload");
                        floatUpload = true;
                        if (uploadIndex < 0) uploadIndex = index;
                    }
                    assertNotEquals("getActiveClipPlaneEquationAfterModelView", call.name);
                }
                index++;
            }
            assertTrue(setup && before && floatUpload && restore);
            assertTrue(uploadIndex > setupIndex && drawIndex > uploadIndex,
                "the late portal scope must be enabled and its plane uploaded before the actual draw");
            assertTrue(skipsIrisOwnedShader, "Iris Extended/Fallback shader ownership takes precedence over a colliding name");
            assertTrue(method.tryCatchBlocks.stream().anyMatch(block -> block.type == null),
                "scoped plane/GL restoration must be protected by finally");
        }
    }
}
