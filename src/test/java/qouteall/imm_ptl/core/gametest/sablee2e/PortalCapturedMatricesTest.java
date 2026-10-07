package qouteall.imm_ptl.core.gametest.sablee2e;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class PortalCapturedMatricesTest {
    public static final class IrisState {
        public static final IrisState INSTANCE = new IrisState();
        Matrix4fc model = new Matrix4f().translation(2, 3, 4);
        public Matrix4fc getGbufferModelView() { return model; }
        public Matrix4fc getGbufferProjection() { return new Matrix4f().scaling(5); }
    }

    public static final class NeOculusState {
        public static final NeOculusState INSTANCE = new NeOculusState();
        public Matrix4f getGbufferModelView() { return new Matrix4f().translation(6, 7, 8); }
        public Matrix4f getGbufferProjection() { return new Matrix4f().scaling(9); }
    }

    @Test
    void cachedBindingsReadBothExactReturnTypesAndFreshMatrices() throws Exception {
        var iris = PortalCapturedMatrices.bind(IrisState.class);
        var neoculus = PortalCapturedMatrices.bind(NeOculusState.class);
        assertEquals(Matrix4fc.class, IrisState.class.getMethod("getGbufferModelView").getReturnType());
        assertEquals(Matrix4f.class, NeOculusState.class.getMethod("getGbufferModelView").getReturnType());
        assertEquals(2, iris.readModelView()[12]);
        assertEquals(5, iris.readProjection()[0]);
        assertEquals(6, neoculus.readModelView()[12]);
        assertEquals(9, neoculus.readProjection()[0]);
        IrisState.INSTANCE.model = new Matrix4f().translation(12, 0, 0);
        assertEquals(12, iris.readModelView()[12]);
        IrisState.INSTANCE.model = null;
        assertNull(iris.readModelView());
        IrisState.INSTANCE.model = new Matrix4f().translation(2, 3, 4);
    }

    @Test
    void diagnosticsHaveNoDirectCapturedRenderingStateMethodLink() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/qouteall/imm_ptl/core/gametest/sablee2e/PortalShaderDiagnostics.class")) {
            assertNotNull(input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        assertNotEquals("net/irisshaders/iris/uniforms/CapturedRenderingState", call.owner,
                            "diagnostics must not hard-link either backend's matrix return descriptor");
                    }
                }
            }
        }
    }
}
