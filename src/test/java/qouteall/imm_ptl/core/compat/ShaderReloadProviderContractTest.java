package qouteall.imm_ptl.core.compat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ShaderReloadProviderContractTest {
    private static final String ROOT = "net/irisshaders/iris/";

    @ParameterizedTest
    @ValueSource(strings = {"ip.irisJar", "ip.neoculusJar"})
    void deferredHooksInterceptBothOneShotReloadsInThePinnedProvider(String property) throws Exception {
        String path = System.getProperty(property);
        assertNotNull(path);
        try (var jar = new ZipFile(path)) {
            var manager = read(jar, ROOT + "pipeline/PipelineManager");
            assertTrue(manager.fields.stream().anyMatch(field -> field.name.equals("pipeline")
                && field.desc.equals("L" + ROOT + "pipeline/WorldRenderingPipeline;")));
            method(manager, "getPipelineNullable", "()L" + ROOT + "pipeline/WorldRenderingPipeline;");
            method(manager, "getVersionCounterForSodiumShaderReload", "()I");
            method(manager, "destroyPipeline", "()V");
            var prepare = method(manager, "preparePipeline", "(L" + ROOT
                + "shaderpack/materialmap/NamespacedId;)L" + ROOT + "pipeline/WorldRenderingPipeline;");
            int materialReload = oneReload(prepare);
            int flagClear = -1;
            for (int i = 0; i < prepare.instructions.size(); i++) {
                if (prepare.instructions.get(i) instanceof MethodInsnNode call
                    && call.name.equals("clearReloadRequired")) flagClear = i;
            }
            assertTrue(flagClear > materialReload, "Provider consumes the request after the wrapped call");

            var begin = method(read(jar, ROOT + "pipeline/IrisRenderingPipeline"), "beginLevelRendering", "()V");
            int blockReload = oneReload(begin);
            int initialized = -1;
            for (int i = 0; i < begin.instructions.size(); i++) {
                if (begin.instructions.get(i) instanceof FieldInsnNode field
                    && field.name.equals("initializedBlockIds") && field.getOpcode() == Opcodes.PUTFIELD) initialized = i;
            }
            assertTrue(initialized > blockReload, "Provider consumes block-mapping initialization after the wrapped call");

            var settings = read(jar, ROOT + "shaderpack/materialmap/WorldRenderingSettings");
            assertTrue(settings.fields.stream().anyMatch(field -> field.name.equals("reloadRequired") && field.desc.equals("Z")));
            assertTrue(settings.fields.stream().anyMatch(field -> field.name.equals("chunkVertexFormat")));
            assertTrue(settings.fields.stream().filter(field -> (field.access & Opcodes.ACC_STATIC) == 0)
                .noneMatch(field -> (field.access & Opcodes.ACC_FINAL) != 0), "Snapshot fields must remain writable");
        }
    }

    private static int oneReload(MethodNode method) {
        int index = -1;
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof MethodInsnNode call
                && call.owner.equals("net/minecraft/client/renderer/LevelRenderer")
                && call.name.equals("allChanged") && call.desc.equals("()V")) {
                assertEquals(-1, index, "The hook must match exactly one provider reload");
                index = i;
            }
        }
        assertTrue(index >= 0, "Missing provider reload in " + method.name);
        return index;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst().orElseThrow(() -> new AssertionError(node.name + "." + name + descriptor));
    }

    private static ClassNode read(ZipFile jar, String name) throws Exception {
        var entry = jar.getEntry(name + ".class");
        assertNotNull(entry, name);
        try (var input = jar.getInputStream(entry)) {
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
