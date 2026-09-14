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
    void deferredCameraTransformUsesPrePacketStateInBothOrderings() throws Exception {
        ClassNode handoff = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableServerFirstClientHandoff.class"
        );
        for (String entry : new String[] {"begin", "prepareServerInitiated"}) {
            MethodNode method = findMethodByName(handoff, entry);
            assertNotNull(method);
            assertTrue(invokesNamed(method, "capturePlayerRotationContext"),
                entry + " must snapshot camera/gravity before authoritative position packets");
        }
        MethodNode ack = findMethodByName(handoff, "acknowledge");
        MethodNode apply = findMethodByName(handoff, "tryApplyReady");
        MethodNode fallback = findMethodByName(handoff, "applyFallbackTransform");
        assertNotNull(ack);
        assertNotNull(apply);
        assertNotNull(fallback);
        assertTrue(invokesNamed(ack, "rotationContext"), "Ack must retain the correlated pre-packet snapshot");
        assertTrue(invokesNamed(ack, "localPitch") && invokesNamed(ack, "localYaw"),
            "Ack must retain the rider-local look captured before Sable seat packets");
        assertTrue(invokesNamed(ack, "tryApplyReady"),
            "Ack must defer final look restoration until the destination rider relation is available");
        assertTrue(invokesNamed(apply, "isAttachedToCurrentSableSubLevel"),
            "successful handoff must gate final look restoration on the destination Sable seat relation");
        assertTrue(invokesNamed(apply, "changePlayerGravity"),
            "deferred rider completion must still apply IP gravity transformation");
        assertTrue(invokesNamed(apply, "setPlayerRawRotation"),
            "destination rider completion must restore the source-local look instead of rotating it twice");
        assertTrue(invokesNamed(fallback, "managePlayerRotationAndChangeGravity"),
            "an unexpectedly missing rider relation must retain a bounded normal-IP fallback");

        ClassNode transformation = readClass("qouteall/imm_ptl/core/render/TransformationManager.class");
        MethodNode capture = findMethodByName(transformation, "capturePlayerRotationContext");
        assertNotNull(capture);
        assertTrue(invokesNamed(capture, "getCameraRotationWithGravity"));
        assertTrue(invokesNamed(capture, "getCurrentAnimationDelta"),
            "active camera interpolation must be included in the snapshot");
        assertTrue(invokesNamed(capture, "getBaseGravityDirection"));
    }

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
    void serverFirstHandshakeCannotStickAndAppliesNormalCameraTransformOnce() throws Exception {
        ClassNode requestMixin = readClass(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinClientTeleportationManager_SableServerFirstAck.class"
        );
        MethodNode request = findMethodByName(requestMixin, "ip_useExplicitServerFirstAcknowledgement");
        assertNotNull(request, "explicit Sable server-first client request hook is missing");
        assertTrue(invokesNamed(request, "hasServerInitiatedHandoff"),
            "client must not create a competing request after a physics-first Prepare arrives");
        assertTrue(invokesNamed(request, "begin"),
            "client-detected ordering must create a correlated handoff before sending its request");
        assertTrue(invokesNamed(request, "send"),
            "client-detected Sable handoff must send the explicit request payload");
        assertTrue(invokesNamed(request, "cancel"),
            "the ambiguous normal teleport packet must not also be sent for a server-first handoff");

        ClassNode requestPacket = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableServerFirstTeleportNetworking$Request.class"
        );
        MethodNode handle = findMethodByName(requestPacket, "handle");
        assertNotNull(handle, "server-first request handler is missing");
        assertTrue(invokesNamed(handle, "onPlayerTeleportedInClient"),
            "successful requests must still execute Immersive Portals' authoritative server path");
        assertTrue(invokesNamed(handle, "forceTeleportPlayer"),
            "rejected or vanished-portal requests must send an authoritative correction");
        assertTrue(invokesNamed(handle, "sendAck"),
            "every handled client request must terminate with an explicit success/failure acknowledgement");
        MethodNode alreadyMigrated = findMethodByName(requestPacket, "acknowledgeAlreadyMigrated");
        assertNotNull(alreadyMigrated, "already-migrated request fast path is missing");
        assertTrue(invokesNamed(alreadyMigrated, "isRiderAlreadyMigrated"));
        assertTrue(invokesNamed(alreadyMigrated, "sendAck"),
            "delayed request after physics-first commit still needs its correlated acknowledgement");
        assertTrue(!invokesNamed(alreadyMigrated, "forceTeleportPlayer"),
            "already-migrated request must not send a second authoritative position/yaw correction");
        assertTrue(invocationIndex(handle, "acknowledgeAlreadyMigrated") < invocationIndex(handle, "onPlayerTeleportedInClient"),
            "already-migrated request must exit before the normal server teleport path can emit another correction");

        ClassNode preparePacket = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableServerFirstTeleportNetworking$Prepare.class"
        );
        MethodNode prepareHandle = findMethodByName(preparePacket, "handle");
        assertNotNull(prepareHandle, "physics-first Prepare payload is missing");
        assertTrue(invokesNamed(prepareHandle, "prepareServerInitiated"),
            "Prepare must install server transform context before the authoritative dimension packet");

        ClassNode migrationMixin = readClass(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSableDimensionStackCompat_ServerFirstRotation.class"
        );
        MethodNode prepareBeforeSeatPackets = findMethodByName(migrationMixin, "ip_prepareBeforeSeatPackets");
        MethodNode correlate = findMethodByName(migrationMixin, "ip_correlateRiderBeforeAuthoritativeMove");
        MethodNode prepareRider = findMethodByName(migrationMixin, "ip_prepareRider");
        MethodNode finish = findMethodByName(migrationMixin, "ip_finishMigrationTransformContext");
        assertNotNull(prepareBeforeSeatPackets, "pre-seat-packet rider preparation hook is missing");
        assertNotNull(correlate, "physics-first rider correlation hook is missing");
        assertNotNull(prepareRider, "shared rider preparation helper is missing");
        assertNotNull(finish, "physics-first terminal acknowledgement hook is missing");
        assertTrue(invokesNamed(prepareRider, "getActiveClientRequestHandoffId"),
            "request-first migration must defer its transform acknowledgement to the outer request handler");
        assertTrue(invokesNamed(prepareRider, "randomUUID"),
            "each pure server-initiated rider migration must use a unique handoff nonce");
        assertTrue(invokesNamed(prepareRider, "sendServerInitiatedPrepare"),
            "pure physics-first migration must send transform context from the shared preparation helper");
        assertTrue(invokesNamed(prepareBeforeSeatPackets, "ip_prepareRider"),
            "Prepare must be emitted before Sable seat detach/mount packets can rewrite client facing");
        int prepareIndex = invocationIndex(correlate, "ip_prepareRider");
        int moveIndex = invocationIndex(correlate, "call");
        assertTrue(prepareIndex >= 0 && moveIndex >= 0 && prepareIndex < moveIndex,
            "fallback rider preparation must still precede teleportEntityGeneral's authoritative dimension packet");
        assertTrue(invokesNamed(finish, "setBaseGravityDirectionServer"),
            "pure physics-first migration must mirror normal IP server gravity transformation after commit");
        assertTrue(invokesNamed(finish, "sendServerInitiatedAck"),
            "physics-first migration must send its terminal Ack only after the transaction returns");

        ClassNode clientHandoff = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableServerFirstClientHandoff.class"
        );
        MethodNode clientPrepare = findMethodByName(clientHandoff, "prepareServerInitiated");
        MethodNode clientAck = findMethodByName(clientHandoff, "acknowledge");
        MethodNode clientApply = findMethodByName(clientHandoff, "tryApplyReady");
        assertNotNull(clientPrepare, "client physics-first Prepare handler is missing");
        assertNotNull(clientAck, "client acknowledgement handler is missing");
        assertNotNull(clientApply, "deferred destination rider completion is missing");
        assertTrue(invokesNamed(clientAck, "tryApplyReady"),
            "successful server-first Ack must attempt ordered completion after the dimension switch");
        assertTrue(invokesNamed(clientApply, "getWorldVelocity"));
        assertTrue(invokesNamed(clientApply, "setWorldVelocity"),
            "deferred look/gravity completion must preserve the already-authoritative world velocity");
        assertTrue(invokesNamed(clientApply, "setPlayerRawRotation"),
            "retained rider completion must restore source-local facing only after Sable reattachment");
        assertTrue(invokesNamed(clientAck, "clearClientPendingGate"),
            "negative or out-of-order acknowledgements must release a client-deferred teleport gate");
    }

    @Test
    void publishedNeoForgeModuleIdsRemainLoadable() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
            "META-INF/neoforge.mods.toml"
        )) {
            assertNotNull(stream, "NeoForge mod metadata is missing");
            String toml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(toml.contains("modId=\"immersive_portals_core\""));
            assertTrue(toml.contains("modId=\"q_misc_util\""),
                "published q_misc_util identity must remain visible to addon dependency resolution");
            assertTrue(toml.contains("modId=\"imm_ptl\""),
                "published imm_ptl identity must remain visible to addon dependency resolution");
        }

        assertTrue(hasAnnotation(
            readClass("qouteall/q_misc_util/MiscUtilModEntry.class"),
            "Lnet/neoforged/fml/common/Mod;"
        ), "q_misc_util must retain its NeoForge @Mod entry point");
        assertTrue(hasAnnotation(
            readClass("qouteall/imm_ptl/peripheral/platform_specific/PeripheralModEntry.class"),
            "Lnet/neoforged/fml/common/Mod;"
        ), "imm_ptl must retain its NeoForge @Mod entry point");
    }

    @Test
    void interpolationAndServerFirstMixinsArePackagedAndRegistered() throws Exception {
        assertNotNull(getClass().getClassLoader().getResource(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat.class"
        ));
        assertNotNull(getClass().getClassLoader().getResource(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinClientTeleportationManager_SableServerFirstAck.class"
        ));
        assertNotNull(getClass().getClassLoader().getResource(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSableDimensionStackCompat_ServerFirstRotation.class"
        ));

        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
            "imm_ptl_compat.mixins.json"
        )) {
            assertNotNull(stream, "compat mixin config is missing");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("sable.MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat"));
            assertTrue(json.contains("sable.MixinClientTeleportationManager_SableServerFirstAck"));
            assertTrue(json.contains("sable.MixinSableDimensionStackCompat_ServerFirstRotation"));
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
        return invocationIndex(method, name) >= 0;
    }

    private static int invocationIndex(MethodNode method, String name) {
        int index = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return index;
            index++;
        }
        return -1;
    }

    private static boolean hasAnnotation(ClassNode node, String descriptor) {
        return node.visibleAnnotations != null
            && node.visibleAnnotations.stream().anyMatch(annotation -> annotation.desc.equals(descriptor));
    }
}
