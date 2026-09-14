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
        MethodNode prepare = findMethodByName(mixin, "ip_prepareRiddenSableBeforePlayer");
        MethodNode acknowledge = findMethodByName(mixin, "ip_acknowledgePreparedRiderAfterPlayerTeleport");
        assertNotNull(prepare, "server-first Sable rider preparation hook is missing");
        assertNotNull(acknowledge, "post-teleport Sable rider acknowledgement hook is missing");

        assertTrue(invokesNamed(prepare, "beforePlayerPortalTeleport"),
            "server-first request must be able to commit the Sable body before player teleport");
        assertTrue(invokesNamed(prepare, "isRiderAlreadyMigrated"),
            "physics-first handoff must avoid transforming the migrated rider twice");
        assertTrue(invokesNamed(prepare, "forceTeleportPlayer"),
            "physics-first handoff and failed migration must send authoritative client corrections");
        assertTrue(invokesNamed(prepare, "cancel"),
            "already-migrated or failed handoffs must stop the normal client-first continuation");

        assertTrue(invokesNamed(acknowledge, "isRiderAlreadyMigrated"),
            "request-first handoff must verify that preparation moved the rider to the destination");
        assertTrue(invokesNamed(acknowledge, "forceTeleportPlayer"),
            "request-first handoff must send a destination-tagged position acknowledgement after normal portal callbacks");
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
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return true;
        }
        return false;
    }
}
