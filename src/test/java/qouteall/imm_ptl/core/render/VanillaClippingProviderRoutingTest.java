package qouteall.imm_ptl.core.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class VanillaClippingProviderRoutingTest {
    @Test
    void installedButDisabledProviderKeepsVanillaUniformUpdateBranch() throws Exception {
        try (var stream = getClass().getResourceAsStream(
            "/qouteall/imm_ptl/core/mixin/client/render/MixinRenderSystem_Clipping.class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            var method = node.methods.stream().filter(m -> m.name.equals("onSetShader")).findFirst().orElseThrow();
            MethodInsnNode activePack = null;
            int uniformUpdates = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    assertNotEquals("isIrisPresent", call.name,
                        "provider installation must not neutralize vanilla entity/particle planes while shaders are off");
                    if (call.name.equals("isShaders")) activePack = call;
                    if (call.name.equals("updateClippingEquationUniformForCurrentShader")) uniformUpdates++;
                }
            }
            assertNotNull(activePack);
            var branch = activePack.getNext();
            while (branch != null && branch.getOpcode() < 0) branch = branch.getNext();
            assertInstanceOf(JumpInsnNode.class, branch);
            assertEquals(Opcodes.IFNE, branch.getOpcode(), "only an active shader pack skips vanilla uploads");
            assertEquals(2, uniformUpdates, "normal/projection entity and weather update routes remain present");
        }
    }
}
