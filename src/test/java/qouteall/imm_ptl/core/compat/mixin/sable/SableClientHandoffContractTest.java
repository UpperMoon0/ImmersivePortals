package qouteall.imm_ptl.core.compat.mixin.sable;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableClientHandoffContractTest {
    @Test
    void destinationFullSyncGraftsSourceInterpolationHistory() throws Exception {
        ClassNode mixin = readClass(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat.class"
        );

        MethodNode capture = findMethodByName(mixin, "ip_captureSourceInterpolation");
        MethodNode graft = findMethodByName(mixin, "ip_graftSourceInterpolation");
        MethodNode transform = findMethodByName(mixin, "ip_transformHistoricalPose");

        assertNotNull(capture, "source interpolation capture is missing");
        assertNotNull(graft, "destination interpolation graft is missing");
        assertNotNull(transform, "historical pose transform is missing");

        assertTrue(invokesNamed(capture, "getServerDimensions"),
            "handoff must search IP's concurrently loaded client worlds");
        assertTrue(invokesNamed(capture, "getInterpolator"),
            "handoff must copy the source Sable interpolation buffer");
        assertTrue(invokesNamed(graft, "getInterpolator"));
        assertTrue(invokesNamed(graft, "setInitialPosesFrom"),
            "destination logical/last poses must be resampled after history graft");
        assertTrue(invokesNamed(graft, "forceUpdateBounds"),
            "destination bounds must match the resampled pose immediately");
        assertTrue(invokesNamed(transform, "premul"),
            "historical orientations must be transformed into destination portal space");
    }

    @Test
    void serverFirstRiderHandoffAcknowledgesBothRaceOrderings() throws Exception {
        ClassNode mixin = readClass(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinServerTeleportationManager_SableRiderCompat.class"
        );
        MethodNode hook = findMethodByName(mixin, "ip_prepareRiddenSableBeforePlayer");
        assertNotNull(hook, "server-first Sable rider handoff hook is missing");

        assertTrue(invokesNamed(hook, "beforePlayerPortalTeleport"),
            "server-first request must be able to commit the Sable body before player teleport");
        assertTrue(invocationCount(hook, "isRiderAlreadyMigrated") >= 2,
            "handoff must re-check destination ownership after the request itself migrates the body");
        assertTrue(invocationCount(hook, "forceTeleportPlayer") >= 3,
            "both successful race orderings and failed migration must send an authoritative client correction");
        assertTrue(invokesNamed(hook, "cancel"),
            "once the server owns the rider in the destination, normal client-first teleport must not run again");
    }

    @Test
    void interpolationMixinIsPackagedAndRegistered() throws Exception {
        assertNotNull(getClass().getClassLoader().getResource(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat.class"
        ));

        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
            "imm_ptl_compat.mixins.json"
        )) {
            assertNotNull(stream, "compat mixin config is missing");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("sable.MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat"));
        }
    }

    private static ClassNode readClass(String resource) throws Exception {
        try (InputStream stream = SableClientHandoffContractTest.class
            .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "missing test-runtime class " + resource);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode findMethodByName(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElse(null);
    }

    private static boolean invokesNamed(MethodNode method, String name) {
        return invocationCount(method, name) != 0;
    }

    private static int invocationCount(MethodNode method, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) count++;
        }
        return count;
    }
}
