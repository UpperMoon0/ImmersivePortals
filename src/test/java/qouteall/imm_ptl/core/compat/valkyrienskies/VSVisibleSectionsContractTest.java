package qouteall.imm_ptl.core.compat.valkyrienskies;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the remote-rendering interception: an early cancellation must not hide VS ships. */
class VSVisibleSectionsContractTest {
    private static ClassNode read(String name) throws IOException {
        try (var stream = VSVisibleSectionsContractTest.class
            .getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    @Test void cancelledSetupCallsVsShipCollector() throws IOException {
        ClassNode renderer = read("qouteall.imm_ptl.core.mixin.client.render.MixinLevelRenderer");
        MethodNode setup = renderer.methods.stream()
            .filter(m -> m.name.equals("onSetupTerrainBegin")).findFirst().orElseThrow();
        int collectorIndex = -1, cancelIndex = -1;
        int i = 0;
        for (AbstractInsnNode instruction : setup.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.endsWith("VSVisibleShipSections")
                    && call.name.equals("appendForRemoteView")) collectorIndex = i;
                if (call.owner.equals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")
                    && call.name.equals("cancel")) cancelIndex = i;
            }
            i++;
        }
        assertTrue(collectorIndex >= 0, "Missing VS ship-section bridge in IP portal terrain setup");
        assertTrue(cancelIndex > collectorIndex,
            "Portal view cancellation runs before the moving-ship sections are collected");
    }

    @Test void cleanupDropsCachedRendererClassReferences() throws IOException {
        ClassNode loader = read("qouteall.imm_ptl.core.ClientWorldLoader");
        MethodNode cleanup = loader.methods.stream()
            .filter(m -> m.name.equals("cleanUp")).findFirst().orElseThrow();
        boolean resets = false;
        for (AbstractInsnNode instruction : cleanup.instructions) {
            if (instruction instanceof MethodInsnNode call
                && call.owner.endsWith("VSVisibleShipSections") && call.name.equals("reset")) {
                resets = true;
            }
        }
        assertTrue(resets, "VS renderer bridge must release references when client worlds are disposed");
    }
}
