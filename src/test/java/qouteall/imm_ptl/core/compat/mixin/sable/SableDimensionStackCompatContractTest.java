package qouteall.imm_ptl.core.compat.mixin.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SubLevelInclusiveLevelEntityGetter;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import qouteall.imm_ptl.core.mixin.common.mc_util.IELevelEntityGetterAdapter;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableDimensionStackCompatContractTest {
    @Test
    void sablePhysicsSubstepsExposeThePortalHookPoint() throws Exception {
        ClassNode physics = readClass(
            "dev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem.class"
        );
        MethodNode tickPipeline = findMethod(
            physics,
            "tickPipelinePhysics",
            "(Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;)V"
        );
        MethodNode updateAllPoses = findMethod(
            physics,
            "updateAllPoses",
            "(Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;)V"
        );
        assertNotNull(tickPipeline, "Sable physics substep pipeline signature changed");
        assertNotNull(updateAllPoses, "Sable pose-copy hook signature changed");
        assertTrue(invokesNamed(tickPipeline, "updateAllPoses"),
            "portal migration must observe every completed physics substep");
        assertTrue(invokesNamed(updateAllPoses, "updatePose"),
            "portal hook must run after Sable copies native poses into logical poses");
    }

    @Test
    void compatUsesSweptCenterOfMassCrossingAndRestoresExactLiveVelocity() throws Exception {
        ClassNode compat = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        );
        MethodNode crossing = findMethodByName(compat, "findCrossedPortal");
        MethodNode centerOfMass = findMethodByName(compat, "getWorldCenterOfMass");
        MethodNode restoreVelocity = findMethodByName(compat, "restoreExactVelocity");
        assertNotNull(crossing, "swept Sable portal crossing detector is missing");
        assertNotNull(centerOfMass, "physical Sable COM anchor is missing");
        assertNotNull(restoreVelocity, "exact live velocity restoration is missing");
        assertTrue(invokesNamed(crossing, "raytracePortals"),
            "Sable crossing must use the real Immersive Portals aperture ray trace");
        assertTrue(invokesNamed(centerOfMass, "getSelfMassTracker"),
            "crossing must use Sable's physical center of mass rather than mutable pose origin");
        assertTrue(invokesNamed(centerOfMass, "transformPosition"),
            "local center of mass must be projected through the current Sable pose");
        assertTrue(invokesNamed(restoreVelocity, "addLinearAndAngularVelocity"),
            "portal migration must overwrite Sable persistence-load velocity damping");
    }

    @Test
    void seamGuardOutlivesNetworkHandoffUntilBodyClearsDestinationPlane() throws Exception {
        ClassNode compat = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        );
        MethodNode substep = findMethodByName(compat, "afterPhysicsSubstep");
        MethodNode clearance = findMethodByName(compat, "signedDestinationClearance");
        MethodNode migrate = findMethodByName(compat, "migrateSubLevel");
        assertNotNull(substep);
        assertNotNull(clearance);
        assertNotNull(migrate);
        assertTrue(invokesNamed(clearance, "subtract"));
        assertTrue(invokesNamed(clearance, "dot"));
        assertTrue(invokesNamed(migrate, "getContentDirection"),
            "handoff guard must retain the destination-facing portal direction");
    }

    @Test
    void destinationIsPreSyncedBeforeRiderTransferAndSourceRetirement() throws Exception {
        ClassNode compat = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        );
        MethodNode migrate = findMethodByName(compat, "migrateSubLevel");
        assertNotNull(migrate, "Sable migration transaction is missing");

        int preSync = invocationIndex(migrate, "beginClientHandoff");
        int entities = invocationIndex(migrate, "transferPlotEntities");
        int sourceRemoval = lastInvocationIndex(migrate, "removeSubLevel");
        int commit = invocationIndex(migrate, "commitClientHandoff");
        assertTrue(preSync >= 0 && entities >= 0 && sourceRemoval >= 0 && commit >= 0,
            "seamless handoff transaction calls are incomplete");
        assertTrue(preSync < entities,
            "destination Sable full sync must be queued before rider/player dimension transfer");
        assertTrue(entities < sourceRemoval,
            "source server owner must survive until the entity graph transfers successfully");
        assertTrue(sourceRemoval < commit,
            "old client copy must not retire until the source server owner is removed");

        MethodNode begin = findMethodByName(compat, "beginClientHandoff");
        assertNotNull(begin);
        assertTrue(invokesNamed(begin, "ip_sendFullSync"),
            "destination full-sync must be explicit rather than waiting for Sable's next tracking tick");
    }

    @Test
    void migrationPreservesDependencyChainsAndForceLoadTickets() throws Exception {
        ClassNode compat = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        );
        MethodNode migrate = findMethodByName(compat, "migrateSubLevel");
        MethodNode stage = findMethodByName(compat, "stageDestinationUnit");
        MethodNode captureTickets = findMethodByName(compat, "captureForceLoadTickets");
        MethodNode installTickets = findMethodByName(compat, "installForceLoadTickets");
        MethodNode removeTickets = findMethodByName(compat, "removeForceLoadTickets");

        assertNotNull(migrate);
        assertNotNull(stage);
        assertNotNull(captureTickets);
        assertNotNull(installTickets);
        assertNotNull(removeTickets);
        assertTrue(invokesNamed(migrate, "getLoadingDependencyChain"),
            "portal transfer must migrate Sable's complete loading dependency chain");
        assertTrue(invokesNamed(stage, "toData"),
            "each migrated dependency must preserve dependency UUID serialization");
        assertTrue(invokesNamed(captureTickets, "collectForceLoadTickets"));
        assertTrue(invokesNamed(installTickets, "addForceLoadTicketUnchecked"));
        assertTrue(invokesNamed(removeTickets, "removeForceLoadTicketUnchecked"));

        assertNotNull(ServerSubLevelContainer.class.getMethod(
            "addForceLoadTicket", ServerSubLevel.class, SubLevelLoadingTicketType.class, Object.class
        ));
        assertNotNull(ServerSubLevelContainer.class.getMethod(
            "removeForceLoadTicket", ServerSubLevel.class, SubLevelLoadingTicketType.class, Object.class
        ));
        assertNotNull(ServerSubLevelContainer.class.getMethod("collectForceLoadTickets"));
    }

    @Test
    void serverPlotAllocationIsGloballyCoordinatedAcrossDimensions() throws Exception {
        ClassNode compat = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        );
        MethodNode allocator = findMethodByName(compat, "findGloballyFreePlot");
        assertNotNull(allocator);
        assertTrue(invokesNamed(allocator, "getAllLevels"));
        assertTrue(invokesNamed(allocator, "getOccupancy"));
        assertTrue(invokesNamed(allocator, "getIndex"));

        ClassNode allocatorMixin = readClass(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSubLevelContainer_SableGlobalPlotAllocator.class"
        );
        MethodNode hook = findMethodByName(allocatorMixin, "ip_allocateGloballyUniquePlot");
        assertNotNull(hook);
        assertTrue(invokesNamed(hook, "findGloballyFreePlot"));
        assertTrue(invokesNamed(hook, "allocateSubLevel"));
    }

    @Test
    void riddenPlayerTeleportIsCancelledWhenBodyHandoffCannotCommit() throws Exception {
        ClassNode riderMixin = readClass(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinServerTeleportationManager_SableRiderCompat.class"
        );
        MethodNode hook = findMethodByName(riderMixin, "ip_prepareRiddenSableBeforePlayer");
        assertNotNull(hook);
        assertTrue(invokesNamed(hook, "beforePlayerPortalTeleport"));
        assertTrue(invokesNamed(hook, "forceTeleportPlayer"),
            "failed body handoff must correct the player back to the source dimension");
        assertTrue(invokesNamed(hook, "cancel"),
            "failed body handoff must cancel the rest of IP's portal-teleport callback");
    }

    @Test
    void remoteTrackingCoversMovementAuxiliaryPacketsAndUdp() throws Exception {
        ClassNode trackingMixin = readClass(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSubLevelTrackingSystem_SablePortalCompat.class"
        );
        assertNotNull(findMethodByName(trackingMixin, "ip_findPortalWatcherDuringMovement"),
            "movement snapshots still use world-local player lookup");
        assertNotNull(findMethodByName(trackingMixin, "ip_routeRemoteSableUdpThroughRedirectedTcp"),
            "remote UDP snapshots still lack destination-world routing");
        assertNotNull(findMethodByName(trackingMixin, "ip_avoidDuplicateDestinationFullSync"),
            "pre-synced destination must suppress Sable's queued duplicate full sync");

        ClassNode subLevelMixin = readClass(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinServerSubLevel_SablePortalCompat.class"
        );
        assertNotNull(findMethodByName(subLevelMixin, "ip_findPortalWatcher"),
            "ServerSubLevel.playerSink still cannot resolve cross-portal watchers");
        assertNotNull(findMethodByName(subLevelMixin, "ip_redirectSubLevelPacket"),
            "ServerSubLevel auxiliary packets still lack IP dimension redirection");
    }

    @Test
    void sableSerializerSupportsCrossLevelReconstruction() throws Exception {
        assertNotNull(SubLevelSerializer.class.getMethod(
            "toData", ServerSubLevel.class, List.class
        ));
        assertNotNull(SubLevelSerializer.class.getMethod(
            "fullyLoad", net.minecraft.server.level.ServerLevel.class, SubLevelData.class
        ));
        assertNotNull(SubLevelContainer.class.getMethod("getContainer", Level.class));
        assertNotNull(ServerSubLevelContainer.class.getMethod("getOccupancy"));
        assertNotNull(ServerSubLevelContainer.class.getMethod("trackingSystem"));
    }

    @Test
    void ridingPassengersRemainPartOfMigration() throws Exception {
        ClassNode sableRidingMixin = readClass(
            "dev/ryanhcode/sable/mixin/entity/entity_rotations_and_riding/EntityMixin.class"
        );
        MethodNode ridingTick = sableRidingMixin.methods.stream()
            .filter(method -> method.name.equals("sable$onRidingTick"))
            .findFirst().orElse(null);
        assertNotNull(ridingTick, "Sable riding mixin changed");
        assertTrue(invokesNamed(ridingTick, "kickRidingEntity"),
            "Sable riders may live in logical world space instead of the hidden plot");

        ClassNode compat = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        );
        MethodNode capture = findMethodByName(compat, "capturePlotEntities");
        assertNotNull(capture, "dimension-stack entity capture is missing");
        assertTrue(invokesNamed(capture, "getPassengers"),
            "dimension migration must follow the recursive riding graph");
        assertTrue(invokesNamed(capture, "kickRidingEntity"),
            "plot-space riders must be converted to logical world space before transfer");
    }

    @Test
    void plotEntityCaptureBypassesSableWrappedLevelEntityGetter() throws Exception {
        assertFalse(IELevelEntityGetterAdapter.class.isAssignableFrom(SubLevelInclusiveLevelEntityGetter.class),
            "Sable deliberately wraps LevelEntityGetterAdapter; compat must not cast that wrapper");

        ClassNode compat = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        );
        MethodNode capture = findMethodByName(compat, "capturePlotEntities");
        assertNotNull(capture);
        assertTrue(invokesNamed(capture, "ip_getEntityManager"));
        assertTrue(invokesNamed(capture, "ip_getSectionStorage"));
        assertFalse(invokesNamed(capture, "portal_getEntityLookup"));
    }

    @Test
    void allSablePortalMixinsArePackaged() throws Exception {
        for (String resource : List.of(
            "qouteall/imm_ptl/core/compat/mixin/sable/AccessorSubLevel_SablePortalCompat.class",
            "qouteall/imm_ptl/core/compat/mixin/sable/InvokerSubLevelTrackingSystem_SablePortalCompat.class",
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinServerSubLevel_SablePortalCompat.class",
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinServerTeleportationManager_SableRiderCompat.class",
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSubLevelContainer_SableGlobalPlotAllocator.class",
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSubLevelPhysicsSystem_SableDimensionStackCompat.class",
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSubLevelTrackingSystem_SablePortalCompat.class"
        )) {
            assertNotNull(getClass().getClassLoader().getResource(resource), resource + " is not packaged");
        }

        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
            "imm_ptl_compat.mixins.json"
        )) {
            assertNotNull(stream, "Compatibility mixin config is missing");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("sable.AccessorSubLevel_SablePortalCompat"));
            assertTrue(json.contains("sable.InvokerSubLevelTrackingSystem_SablePortalCompat"));
            assertTrue(json.contains("sable.MixinServerSubLevel_SablePortalCompat"));
            assertTrue(json.contains("sable.MixinServerTeleportationManager_SableRiderCompat"));
            assertTrue(json.contains("sable.MixinSubLevelContainer_SableGlobalPlotAllocator"));
            assertTrue(json.contains("sable.MixinSubLevelPhysicsSystem_SableDimensionStackCompat"));
            assertTrue(json.contains("sable.MixinSubLevelTrackingSystem_SablePortalCompat"));
        }
    }

    private static ClassNode readClass(String resource) throws Exception {
        try (InputStream stream = SableDimensionStackCompatContractTest.class
            .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "Missing test-runtime class " + resource);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode findMethod(String resource, String name, String descriptor) throws Exception {
        return findMethod(readClass(resource), name, descriptor);
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
            .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst().orElse(null);
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

    private static int lastInvocationIndex(MethodNode method, String name) {
        int index = 0;
        int found = -1;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) found = index;
            index++;
        }
        return found;
    }
}
