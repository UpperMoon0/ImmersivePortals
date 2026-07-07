package qouteall.imm_ptl.core.compat.mixin.sable;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SablePortalCompatContractTest {
    private static final String TARGET =
        "dev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem.class";

    @Test
    void sableTrackingBytecodeMatchesMixinContract() throws Exception {
        ClassNode target = readClass(TARGET);

        assertMethod(target, "shouldLoad", "(Lnet/minecraft/world/entity/player/Player;Lorg/joml/Vector3dc;)Z");
        assertMethod(target, "collectPlayers", "(Lorg/joml/Vector3d;Ljava/util/Collection;)V");

        MethodNode tick = findMethod(target, "tick", "(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V");
        assertNotNull(tick, "Sable tracking tick signature changed");
        assertTrue(tick.instructions.iterator().hasNext(), "Sable tracking tick has no bytecode");
        int matchingPlayerLookups = 0;
        List<String> playerLookups = new ArrayList<>();
        for (var instruction : tick.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("getPlayerByUUID")) {
                playerLookups.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/server/level/ServerLevel")
                    && call.desc.equals("(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;")) {
                    matchingPlayerLookups++;
                }
            }
        }
        assertTrue(matchingPlayerLookups == 2,
            "Expected two player lookups targeted by the portal watcher redirect, found: " + playerLookups);
    }

    @Test
    void compatMixinIsPackagedAndEnabled() throws Exception {
        assertNotNull(getClass().getClassLoader().getResource(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSubLevelTrackingSystem_SablePortalCompat.class"));
        assertNotNull(getClass().getClassLoader().getResource(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat.class"));
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("imm_ptl_compat.mixins.json")) {
            assertNotNull(stream, "Compatibility mixin config is missing");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("sable.MixinSubLevelTrackingSystem_SablePortalCompat"));
            assertTrue(json.contains("sable.MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat"));
        }
    }

    private static ClassNode readClass(String resource) throws Exception {
        try (InputStream stream = SablePortalCompatContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "Sable is missing from the test runtime");
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static void assertMethod(ClassNode node, String name, String descriptor) {
        assertNotNull(findMethod(node, name, descriptor), name + " descriptor changed: expected " + descriptor);
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
            .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst()
            .orElse(null);
    }
}
