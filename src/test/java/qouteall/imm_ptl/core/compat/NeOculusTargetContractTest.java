package qouteall.imm_ptl.core.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Read NeOculus separately: putting both Iris implementations on the test classpath hides ABI errors. */
class NeOculusTargetContractTest {
    private static final String IRIS = "net/irisshaders/iris/";

    @Test
    void embeddiumShaderHasTheExactConstructorAndNoSodiumShaderAlias() throws Exception {
        try (var jar = openJar()) {
            var shader = read(jar, IRIS + "compat/embeddium/impl/oculus/EmbeddiumShader");
            assertEquals("org/embeddedt/embeddium/impl/render/chunk/shader/ChunkShaderInterface", shader.superName);
            method(shader, "<init>", "(L" + IRIS + "pipeline/IrisRenderingPipeline;L" + IRIS
                + "compat/embeddium/impl/oculus/EmbeddiumPrograms$Pass;"
                + "Lorg/embeddedt/embeddium/impl/render/chunk/shader/ShaderBindingContext;IL" + IRIS
                + "gl/blending/BlendModeOverride;Ljava/util/List;L" + IRIS
                + "uniforms/custom/CustomUniforms;Ljava/util/function/Supplier;FZ)V");
            method(shader, "setupState", "()V");
            assertNull(jar.getEntry(IRIS + "pipeline/programs/SodiumShader.class"));
        }
    }

    @Test
    void sharedPipelineHooksAndInterfaceCallsMatchNeOculus() throws Exception {
        try (var jar = openJar()) {
            var pipeline = read(jar, IRIS + "pipeline/IrisRenderingPipeline");
            field(pipeline, "isRenderingWorld", "Z");
            field(pipeline, "isBeforeTranslucent", "Z");
            method(pipeline, "finalizeLevelRendering", "()V");
            method(pipeline, "beginTranslucents", "()V");
            var translucents = pipeline.methods.stream().filter(m -> m.name.equals("beginTranslucents")).findFirst().orElseThrow();
            assertTrue(java.util.stream.StreamSupport.stream(translucents.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(IRIS + "pipeline/CompositeRenderer")
                    && call.name.equals("renderAll") && call.desc.equals("()V")));
            method(read(jar, IRIS + "targets/ClearPass"), "execute", "(Lorg/joml/Vector4f;)V");
            method(read(jar, IRIS + "pipeline/FinalPassRenderer"), "renderFinalPass", "()V");
            read(jar, IRIS + "shadows/ShadowRenderTargets");
            field(read(jar, IRIS + "shadows/ShadowRenderer"), "ACTIVE", "Z");
            method(read(jar, IRIS + "shadows/ShadowRenderer"), "renderShadows",
                "(L" + IRIS + "mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V");
            method(read(jar, IRIS + "pathways/FullScreenQuadRenderer"), "renderQuad", "()V");
            var iris = read(jar, IRIS + "Iris");
            method(iris, "getCurrentPack", "()Ljava/util/Optional;");
            method(iris, "getCurrentPackName", "()Ljava/lang/String;");
            method(iris, "getPipelineManager", "()L" + IRIS + "pipeline/PipelineManager;");
            var manager = read(jar, IRIS + "pipeline/PipelineManager");
            method(manager, "destroyPipeline", "()V");
            method(manager, "getPipeline", "()Ljava/util/Optional;");
            field(read(jar, IRIS + "mixin/MixinLevelRenderer"), "pipeline", "L" + IRIS + "pipeline/WorldRenderingPipeline;");
            method(read(jar, IRIS + "pipeline/transform/TransformPatcher"), "transformInternal",
                "(Ljava/lang/String;Ljava/util/Map;L" + IRIS + "pipeline/transform/parameter/Parameters;)Ljava/util/Map;");
        }
    }

    private static ZipFile openJar() throws Exception {
        String path = System.getProperty("ip.neoculusJar");
        assertNotNull(path, "The test task must resolve the pinned NeOculus contract artifact separately from Iris");
        return new ZipFile(path);
    }

    private static ClassNode read(ZipFile jar, String name) throws Exception {
        var entry = jar.getEntry(name + ".class");
        assertNotNull(entry, "Missing NeOculus target " + name);
        try (InputStream input = jar.getInputStream(entry)) {
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static void field(ClassNode node, String name, String descriptor) {
        assertTrue(node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor)), node.name + "." + name + ":" + descriptor);
    }

    private static void method(ClassNode node, String name, String descriptor) {
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)), node.name + "." + name + descriptor);
    }
}
