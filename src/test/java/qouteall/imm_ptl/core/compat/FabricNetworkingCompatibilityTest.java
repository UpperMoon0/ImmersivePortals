package qouteall.imm_ptl.core.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.io.File;
import java.util.List;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;

class FabricNetworkingCompatibilityTest {
    private static final List<String> TARGETS = List.of(
        "net/fabricmc/fabric/impl/networking/PayloadTypeRegistryImpl",
        "org/sinytra/fabric/networking_api/NeoNetworkRegistrar",
        "org/sinytra/fabric/networking_api/NeoNetworkRegistrar$NeoPayloadHandler");

    private ClassNode read(ZipFile jar, String name) throws Exception {
        var node = new ClassNode();
        try (var stream = jar.getInputStream(jar.getEntry(name + ".class"))) {
            new ClassReader(stream).accept(node, 0);
        }
        return node;
    }

    @Test void baselineTxnilibAndLatestNetworkingRetainEveryInjectionTarget() throws Exception {
        String[] jars = System.getProperty("ip.fabricNetworkingJars").split(java.util.regex.Pattern.quote(File.pathSeparator));
        assertEquals(3, jars.length);
        for (String path : jars) try (var jar = new ZipFile(path)) {
            for (String name : TARGETS) {
                var target = read(jar, name);
                assertDoesNotThrow(() -> FabricNetworkingCompatibility.check(target, new File(path).getName()));
            }
        }
    }

    @Test void changedFieldsAndReceiverDescriptorsFailWithActionableDiagnostics() throws Exception {
        try (var jar = new ZipFile(System.getProperty("ip.fabricNetworkingJars").split(java.util.regex.Pattern.quote(File.pathSeparator))[0])) {
            for (String name : TARGETS) {
                var target = read(jar, name);
                target.fields.removeIf(f -> f.desc.equals("Ljava/util/Map;"));
                var failure = assertThrows(IllegalStateException.class,
                    () -> FabricNetworkingCompatibility.check(target, "future-compatible-version-label"));
                assertTrue(failure.getMessage().contains("future-compatible-version-label"));
                assertTrue(failure.getMessage().contains(name));
            }
            var target = read(jar, TARGETS.get(2));
            target.methods.stream().filter(m -> m.name.equals("registerLocalReceiver")).forEach(m -> m.desc = "()Z");
            assertThrows(IllegalStateException.class, () -> FabricNetworkingCompatibility.check(target, "4.3.1"));
        }
    }

    @Test void removedOrDuplicatedRedirectSitesFailClosed() throws Exception {
        try (var jar = new ZipFile(System.getProperty("ip.fabricNetworkingJars").split(java.util.regex.Pattern.quote(File.pathSeparator))[0])) {
            for (String name : TARGETS.subList(0, 2)) {
                for (boolean duplicate : new boolean[]{false, true}) {
                    var target = read(jar, name);
                    String methodName = name.endsWith("NeoNetworkRegistrar") ? "getOrRegisterNativeHandler" : "register";
                    var method = target.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
                    for (var instruction : method.instructions.toArray()) {
                        if (instruction instanceof MethodInsnNode call && call.owner.equals("java/util/Map")
                            && (call.name.equals("put") || call.name.equals("computeIfAbsent"))) {
                            if (duplicate) method.instructions.insert(call, new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
                            else method.instructions.remove(call);
                        }
                    }
                    assertThrows(IllegalStateException.class, () -> FabricNetworkingCompatibility.check(target, "new-version"));
                }
            }
        }
    }
}
