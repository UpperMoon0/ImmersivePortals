package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class PortalShaderToggleContractTest {
    @Test
    void bothPinnedBackendsExposeTheUserActionThatPersistsBeforeReload() throws Exception {
        for (String property : List.of("ip.irisJar", "ip.neoculusJar")) {
            assertNotNull(System.getProperty(property));
            try (var jar = new ZipFile(System.getProperty(property));
                 var input = jar.getInputStream(jar.getEntry("net/irisshaders/iris/Iris.class"))) {
                var node = new ClassNode();
                new ClassReader(input).accept(node, 0);
                var toggle = node.methods.stream().filter(method -> method.name.equals("toggleShaders")
                    && method.desc.equals("(Lnet/minecraft/client/Minecraft;Z)V")).findFirst().orElseThrow();
                var calls = new ArrayList<String>();
                for (var instruction : toggle.instructions) {
                    if (instruction instanceof MethodInsnNode call) calls.add(call.name);
                }
                assertTrue(calls.indexOf("setShadersEnabled") >= 0, property);
                assertTrue(calls.indexOf("save") > calls.indexOf("setShadersEnabled"), property);
                assertTrue(calls.indexOf("reload") > calls.indexOf("save"), property);
                var reload = node.methods.stream().filter(method -> method.name.equals("reload")
                    && method.desc.equals("()V")).findFirst().orElseThrow();
                assertTrue(java.util.stream.StreamSupport.stream(reload.instructions.spliterator(), false)
                    .anyMatch(instruction -> instruction instanceof MethodInsnNode call && call.name.equals("initialize")),
                    "The observed failure depends on reload re-reading persistent config");
            }
        }
    }

    @Test
    void liveHarnessUsesTheUpstreamToggleInsteadOfAnUnsavedConfigMutation() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
            "qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeClient.class")) {
            assertNotNull(input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            var toggle = node.methods.stream().filter(method -> method.name.equals("toggleShaders")
                && method.desc.equals("(Z)V")).findFirst().orElseThrow();
            List<Object> constants = new ArrayList<>();
            for (var instruction : toggle.instructions) {
                if (instruction instanceof LdcInsnNode constant) constants.add(constant.cst);
            }
            assertTrue(constants.contains("toggleShaders"));
            assertFalse(constants.contains("setShadersEnabled"));
            assertFalse(constants.contains("reload"));
        }
    }
}
