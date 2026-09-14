package qouteall.imm_ptl.core.compat.sable;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinhelpers.entity.entity_riding_sub_level_vehicle.EntityRidingSubLevelVehicleHelper;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin.sable.AccessorSubLevel_SablePortalCompat;
import qouteall.imm_ptl.core.compat.mixin.sable.InvokerSubLevelTrackingSystem_SablePortalCompat;
import qouteall.imm_ptl.core.ducks.IEServerEntityManager;
import qouteall.imm_ptl.core.ducks.IEServerWorld;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Seamless runtime bridge between Sable sublevels and Immersive Portals. */
public final class SableDimensionStackCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CLIENT_HANDOFF_TIMEOUT_TICKS = 100;
    private static final double HANDOFF_CLEARANCE = 1.0e-7;
    private static final double PORTAL_ENDPOINT_EPSILON = 1.0e-7;
    private static final Map<UUID, CrossingSample> LAST_SAMPLES = new HashMap<>();
    private static final Map<UUID, ClientHandoff> CLIENT_HANDOFFS = new HashMap<>();
    private static final Map<UUID, HandoffGuard> HANDOFF_GUARDS = new HashMap<>();

    private SableDimensionStackCompat() {}

    /** Invoked after each completed Sable native physics substep. */
    public static void afterPhysicsSubstep(ServerLevel level, ServerSubLevelContainer container) {
        expireStaleClientHandoffs(level);

        for (ServerSubLevel subLevel : List.copyOf(container.getAllSubLevels())) {
            if (subLevel.isRemoved()) continue;

            UUID id = subLevel.getUniqueId();
            Vec3 currentAnchor = getWorldCenterOfMass(subLevel, subLevel.logicalPose());
            CrossingSample previousSample = LAST_SAMPLES.get(id);
            Vec3 previousAnchor = previousSample != null
                && previousSample.dimension().equals(level.dimension())
                ? previousSample.anchor()
                : getWorldCenterOfMass(subLevel, new Pose3d(subLevel.lastPose()));

            HandoffGuard guard = HANDOFF_GUARDS.get(id);
            if (guard != null && guard.destinationDimension().equals(level.dimension())) {
                // A rider can trigger migration before the body's COM enters the destination.
                // Once either sample is inside, a reverse sweep is real motion (including
                // immediate gravity return), even when penetration was less than 0.25 blocks.
                double previousClearance = signedDestinationClearance(guard, previousAnchor);
                double currentClearance = signedDestinationClearance(guard, currentAnchor);
                boolean enteredDestination = Math.max(previousClearance, currentClearance) >= HANDOFF_CLEARANCE;
                boolean reversedFromSeam = Math.abs(previousClearance) <= HANDOFF_CLEARANCE
                    && currentClearance < -HANDOFF_CLEARANCE;
                if (enteredDestination || reversedFromSeam) {
                    HANDOFF_GUARDS.remove(id);
                    guard = null;
                }
            }

            ClientHandoff pendingHandoff = CLIENT_HANDOFFS.get(id);
            if (pendingHandoff != null && !pendingHandoff.committed) {
                rememberSample(level, id, currentAnchor);
                continue;
            }

            Portal portal = findCrossedPortal(level, previousAnchor, currentAnchor);
            if (portal == null) {
                rememberSample(level, id, currentAnchor);
                continue;
            }

            if (guard != null
                && portal.getDestDim().equals(guard.sourceDimension())
                && signedDestinationClearance(guard, currentAnchor) < HANDOFF_CLEARANCE) {
                rememberSample(level, id, currentAnchor);
                continue;
            }

            ServerSubLevel migrated = migrateSubLevel(
                container, subLevel, portal, new Pose3d(subLevel.lastPose())
            );
            if (migrated == null) {
                rememberSample(level, id, currentAnchor);
            }
        }
    }

    /**
     * Move the ridden Sable body before IP changes the player's world. This closes the race
     * where the client can enter the destination dimension before that dimension owns the
     * sublevel the rider is sitting on.
     */
    public static boolean isRiderAlreadyMigrated(ServerPlayer player, Portal portal) {
        if (!player.serverLevel().dimension().equals(portal.getDestDim())) return false;

        Entity vehicle = player.getVehicle();
        while (vehicle != null) {
            SubLevel containing = Sable.HELPER.getContaining(vehicle);
            if (containing instanceof ServerSubLevel serverSubLevel) {
                return serverSubLevel.getLevel() == player.serverLevel();
            }
            vehicle = vehicle.getVehicle();
        }
        return false;
    }
    public static boolean beforePlayerPortalTeleport(ServerPlayer player, Portal portal) {
        Entity vehicle = player.getVehicle();
        while (vehicle != null) {
            SubLevel containing = Sable.HELPER.getContaining(vehicle);
            if (containing instanceof ServerSubLevel sourceSubLevel) {
                // Physics can commit the body+rider handoff before the client's portal notification
                // reaches the server. Treat that delayed acknowledgement as success; sending the
                // fallback source correction here would split the rider back from the migrated body.
                if (sourceSubLevel.getLevel() == player.serverLevel()
                    && player.serverLevel().dimension().equals(portal.getDestDim())) {
                    return true;
                }
                if (sourceSubLevel.getLevel() != player.serverLevel()) return false;
                ServerSubLevelContainer sourceContainer = SubLevelContainer.getContainer(player.serverLevel());
                if (sourceContainer == null) return false;
                if (CLIENT_HANDOFFS.containsKey(sourceSubLevel.getUniqueId())) return true;
                return migrateSubLevel(
                    sourceContainer, sourceSubLevel, portal, new Pose3d(sourceSubLevel.lastPose())
                ) != null;
            }
            vehicle = vehicle.getVehicle();
        }
        return true;
    }

    /**
     * Use one hidden plot coordinate across every server dimension so live portal migration does
     * not need to rewrite arbitrary block-entity/attachment NBT containing hidden-world coords.
     */
    public static Vector2i findGloballyFreePlot(
        ServerLevel allocatingLevel, SubLevelContainer requestingContainer
    ) {
        int sideLength = 1 << requestingContainer.getLogSideLength();
        int logPlotSize = requestingContainer.getLogPlotSize();

        for (int x = 0; x < sideLength; x++) {
            for (int z = 0; z < sideLength; z++) {
                boolean free = true;
                for (ServerLevel level : allocatingLevel.getServer().getAllLevels()) {
                    ServerSubLevelContainer other = SubLevelContainer.getContainer(level);
                    if (other == null) continue;
                    if (other.getLogSideLength() != requestingContainer.getLogSideLength()
                        || other.getLogPlotSize() != logPlotSize) {
                        throw new IllegalStateException(
                            "Sable plot grids differ between dimensions; seamless cross-dimension allocation is unsafe"
                        );
                    }
                    if (other.getOccupancy().get(other.getIndex(x, z))) {
                        free = false;
                        break;
                    }
                }
                if (free) return new Vector2i(x, z);
            }
        }
        return null;
    }

    private static void rememberSample(ServerLevel level, UUID id, Vec3 anchor) {
        LAST_SAMPLES.put(id, new CrossingSample(level.dimension(), anchor, level.getGameTime()));
    }

    /** Use physical COM because Sable may recenter Pose3d.position() during serialization. */
    private static Vec3 getWorldCenterOfMass(ServerSubLevel subLevel, Pose3d pose) {
        Vector3dc localCenterOfMass = subLevel.getSelfMassTracker().getCenterOfMass();
        return JOMLConversion.toMojang(pose.transformPosition(new Vector3d(localCenterOfMass)));
    }

    private static Portal findCrossedPortal(ServerLevel level, Vec3 previous, Vec3 current) {
        Vec3 delta = current.subtract(previous);
        double lengthSqr = delta.lengthSqr();
        if (lengthSqr < 1.0e-14) return null;

        // RectangularPortalShape deliberately uses strict from>0/to<0 plane tests. Extend the
        // sweep only by numerical epsilon so a native physics sample that lands exactly on the
        // plane is handled in that substep instead of being lost before the next sample.
        Vec3 endpointOffset = delta.scale(PORTAL_ENDPOINT_EPSILON / Math.sqrt(lengthSqr));
        Vec3 traceFrom = previous.subtract(endpointOffset);
        Vec3 traceTo = current.add(endpointOffset);

        return PortalUtils.raytracePortals(
            level, traceFrom, traceTo, true,
            portal -> {
                double previousDistance = portal.getDistanceToPlane(previous);
                double currentDistance = portal.getDistanceToPlane(current);
                return portal.isTeleportable()
                    && !portal.hasScaling()
                    && !portal.getDestDim().equals(level.dimension())
                    && previousDistance >= -PORTAL_ENDPOINT_EPSILON
                    && currentDistance <= PORTAL_ENDPOINT_EPSILON
                    && currentDistance < previousDistance;
            }
        ).map(Pair::getFirst).orElse(null);
    }

    private static double signedDestinationClearance(HandoffGuard guard, Vec3 anchor) {
        return anchor.subtract(guard.destinationPlane()).dot(guard.destinationDirection());
    }

    /** Migrate the complete Sable loading dependency chain as one commit. */
    private static ServerSubLevel migrateSubLevel(
        ServerSubLevelContainer sourceContainer,
        ServerSubLevel sourceSubLevel,
        Portal portal,
        Pose3d previousPhysicsPose
    ) {
        ServerLevel sourceWorld = sourceContainer.getLevel();
        ServerLevel destinationWorld = sourceWorld.getServer().getLevel(portal.getDestDim());
        if (destinationWorld == null || destinationWorld == sourceWorld) return null;

        ServerSubLevelContainer destinationContainer = SubLevelContainer.getContainer(destinationWorld);
        if (destinationContainer == null) {
            LOGGER.error(
                "Cannot migrate Sable sublevel {} through portal: destination {} has no Sable container",
                sourceSubLevel.getUniqueId(), portal.getDestDim().location()
            );
            return null;
        }
        if (destinationContainer.getLogSideLength() != sourceContainer.getLogSideLength()
            || destinationContainer.getLogPlotSize() != sourceContainer.getLogPlotSize()) {
            LOGGER.error("Cannot migrate Sable sublevel {}: source/destination plot grids differ",
                sourceSubLevel.getUniqueId());
            return null;
        }

        List<ServerSubLevel> chain = new ArrayList<>(SubLevelHelper.getLoadingDependencyChain(sourceSubLevel));
        if (!chain.contains(sourceSubLevel)) chain.add(sourceSubLevel);
        chain.removeIf(SubLevel::isRemoved);
        chain.sort(Comparator.comparing(subLevel -> subLevel.getUniqueId().toString()));
        for (ServerSubLevel member : chain) {
            if (member.getLevel() != sourceWorld) {
                LOGGER.error(
                    "Cannot migrate Sable dependency chain {}: member {} belongs to another level",
                    sourceSubLevel.getUniqueId(), member.getUniqueId()
                );
                return null;
            }
        }

        List<UUID> dependencyIds = chain.stream().map(SubLevel::getUniqueId).toList();
        for (ServerSubLevel member : chain) {
            int localX = localPlotX(sourceContainer, member);
            int localZ = localPlotZ(sourceContainer, member);
            if (!isMatchingDestinationSlotFree(destinationContainer, localX, localZ)) {
                SubLevel occupant = destinationContainer.getSubLevel(localX, localZ);
                LOGGER.error(
                    "Cannot seamlessly migrate Sable chain {} from {} to {}: legacy plot collision at {},{} occupant={}. "
                        + "New allocations are globally coordinated; this collision predates the compatibility lease.",
                    sourceSubLevel.getUniqueId(), sourceWorld.dimension().location(),
                    destinationWorld.dimension().location(), localX, localZ,
                    occupant == null ? "unloaded/reserved" : occupant.getUniqueId()
                );
                return null;
            }
        }

        List<MigrationUnit> units = new ArrayList<>();
        try {
            for (ServerSubLevel member : chain) {
                Pose3d previousPose = member == sourceSubLevel
                    ? new Pose3d(previousPhysicsPose)
                    : new Pose3d(member.lastPose());
                units.add(stageDestinationUnit(
                    sourceContainer, destinationContainer, member, portal,
                    previousPose, dependencyIds
                ));
            }

            for (MigrationUnit unit : units) {
                beginClientHandoff(
                    sourceWorld, destinationContainer, unit.source(), unit.destination(),
                    ChunkPos.asLong(unit.localPlotX(), unit.localPlotZ())
                );
            }
        }
        catch (RuntimeException stagingFailure) {
            cleanupStagedUnits(destinationContainer, units);
            LOGGER.error("Failed staging seamless Sable destination; source chain kept", stagingFailure);
            return null;
        }

        try {
            for (MigrationUnit unit : units) {
                transferPlotEntities(unit.entities(), destinationWorld, unit.movedById());
            }
        }
        catch (RuntimeException transferFailure) {
            rollbackAllEntities(units, sourceWorld);
            cleanupStagedUnits(destinationContainer, units);
            LOGGER.error("Failed Sable dependency-chain entity migration; source chain kept", transferFailure);
            return null;
        }

        // This is the last point at which complete rollback is possible. Do not touch source
        // tickets or source sublevels unless every member still owns exactly the plot we staged.
        if (!verifySourceOwnership(sourceContainer, units)) {
            rollbackAllEntities(units, sourceWorld);
            cleanupStagedUnits(destinationContainer, units);
            LOGGER.error("Sable source ownership changed while a portal handoff was staged; transaction aborted");
            return null;
        }

        try {
            for (MigrationUnit unit : units) {
                removeForceLoadTickets(sourceContainer, unit.source(), unit.tickets());
            }
        }
        catch (RuntimeException ticketFailure) {
            for (MigrationUnit unit : units) {
                restoreForceLoadTickets(sourceContainer, unit.source(), unit.tickets());
            }
            rollbackAllEntities(units, sourceWorld);
            cleanupStagedUnits(destinationContainer, units);
            LOGGER.error("Failed transferring Sable force-load tickets; source chain kept", ticketFailure);
            return null;
        }

        for (MigrationUnit unit : units) {
            sourceContainer.removeSubLevel(unit.source(), SubLevelRemovalReason.REMOVED);
        }
        for (MigrationUnit unit : units) {
            commitClientHandoff(sourceWorld, unit.source().getUniqueId());
            Vec3 destinationAnchor = getWorldCenterOfMass(unit.destination(), unit.destination().logicalPose());
            rememberSample(destinationWorld, unit.destination().getUniqueId(), destinationAnchor);
            HANDOFF_GUARDS.put(unit.destination().getUniqueId(), new HandoffGuard(
                sourceWorld.dimension(), destinationWorld.dimension(), portal.getDestPos(),
                portal.getContentDirection().normalize()
            ));
        }

        ServerSubLevel migratedSource = units.stream()
            .filter(unit -> unit.source() == sourceSubLevel)
            .map(MigrationUnit::destination)
            .findFirst().orElse(null);
        LOGGER.debug(
            "Migrated Sable dependency chain rooted at {} ({} bodies) from {} to {} through portal {}",
            sourceSubLevel.getUniqueId(), units.size(), sourceWorld.dimension().location(),
            destinationWorld.dimension().location(), portal.getUUID()
        );
        return migratedSource;
    }

    private static boolean verifySourceOwnership(
        ServerSubLevelContainer sourceContainer, List<MigrationUnit> units
    ) {
        for (MigrationUnit unit : units) {
            if (unit.source().isRemoved()) return false;
            if (sourceContainer.getSubLevel(unit.localPlotX(), unit.localPlotZ()) != unit.source()) {
                return false;
            }
        }
        return true;
    }

    private static MigrationUnit stageDestinationUnit(
        ServerSubLevelContainer sourceContainer,
        ServerSubLevelContainer destinationContainer,
        ServerSubLevel sourceSubLevel,
        Portal portal,
        Pose3d previousPhysicsPose,
        List<UUID> dependencyIds
    ) {
        ServerLevel sourceWorld = sourceContainer.getLevel();
        ServerLevel destinationWorld = destinationContainer.getLevel();
        RigidBodyHandle sourceHandle = RigidBodyHandle.of(sourceSubLevel);
        if (sourceHandle == null || !sourceHandle.isValid()) {
            throw new IllegalStateException(
                "Source Sable physics handle unavailable for " + sourceSubLevel.getUniqueId()
            );
        }

        Vector3d exactLinearVelocity = sourceHandle.getLinearVelocity(new Vector3d());
        Vector3d exactAngularVelocity = sourceHandle.getAngularVelocity(new Vector3d());
        Vector3d destinationLinearVelocity = JOMLConversion.toJOML(
            portal.transformLocalVec(JOMLConversion.toMojang(exactLinearVelocity))
        );
        Vector3d destinationAngularVelocity = JOMLConversion.toJOML(
            portal.transformLocalVecNonScale(JOMLConversion.toMojang(exactAngularVelocity))
        );

        List<EntityTransfer> entities = capturePlotEntities(sourceWorld, sourceSubLevel, portal);
        List<SubLevelLoadingTicket<?>> tickets = captureForceLoadTickets(sourceContainer, sourceSubLevel);
        SubLevelData sourceData = SubLevelSerializer.toData(sourceSubLevel, dependencyIds);
        SubLevelData destinationData = transformSerializedState(sourceData, portal);
        if (!rebasePlotSections(
            destinationData.fullTag(), sourceWorld.getMinSection(),
            destinationWorld.getMinSection(), destinationWorld.getSectionsCount()
        )) {
            throw new IllegalStateException(
                "Sable blocks exceed destination build height for " + sourceSubLevel.getUniqueId()
            );
        }

        ServerSubLevel destinationSubLevel = SubLevelSerializer.fullyLoad(destinationWorld, destinationData);
        if (destinationSubLevel == null) {
            throw new IllegalStateException(
                "Failed loading migrated Sable sublevel " + sourceSubLevel.getUniqueId()
            );
        }

        try {
            restoreExactVelocity(destinationSubLevel, destinationLinearVelocity, destinationAngularVelocity);
            destinationSubLevel.latestLinearVelocity.set(destinationLinearVelocity);
            destinationSubLevel.latestAngularVelocity.set(destinationAngularVelocity);
            ((AccessorSubLevel_SablePortalCompat) (Object) destinationSubLevel)
                .ip_getLastPose().set(transformPose(previousPhysicsPose, portal));
            installForceLoadTickets(destinationContainer, destinationSubLevel, tickets);
        }
        catch (RuntimeException failure) {
            removeForceLoadTicketsBestEffort(destinationContainer, destinationSubLevel, tickets);
            destinationContainer.removeSubLevel(destinationSubLevel, SubLevelRemovalReason.REMOVED);
            throw failure;
        }

        return new MigrationUnit(
            sourceSubLevel, destinationSubLevel,
            localPlotX(sourceContainer, sourceSubLevel),
            localPlotZ(sourceContainer, sourceSubLevel),
            entities, tickets, new HashMap<>()
        );
    }

    private static int localPlotX(ServerSubLevelContainer container, ServerSubLevel subLevel) {
        return subLevel.getPlot().plotPos.x - container.getOrigin().x;
    }

    private static int localPlotZ(ServerSubLevelContainer container, ServerSubLevel subLevel) {
        return subLevel.getPlot().plotPos.z - container.getOrigin().y;
    }

    private static List<SubLevelLoadingTicket<?>> captureForceLoadTickets(
        ServerSubLevelContainer container, ServerSubLevel subLevel
    ) {
        Set<SubLevelLoadingTicket<?>> tickets = container.collectForceLoadTickets().get(subLevel);
        return tickets == null ? List.of() : List.copyOf(tickets);
    }

    private static void installForceLoadTickets(
        ServerSubLevelContainer container,
        ServerSubLevel subLevel,
        List<SubLevelLoadingTicket<?>> tickets
    ) {
        for (SubLevelLoadingTicket<?> ticket : tickets) {
            addForceLoadTicketUnchecked(container, subLevel, ticket);
        }
        Set<SubLevelLoadingTicket<?>> active = container.collectForceLoadTickets().get(subLevel);
        if (!tickets.isEmpty() && (active == null || !active.containsAll(tickets))) {
            throw new IllegalStateException("Destination Sable force-load ticket set is incomplete");
        }
    }

    private static void removeForceLoadTickets(
        ServerSubLevelContainer container,
        ServerSubLevel subLevel,
        List<SubLevelLoadingTicket<?>> tickets
    ) {
        for (SubLevelLoadingTicket<?> ticket : tickets) {
            removeForceLoadTicketUnchecked(container, subLevel, ticket);
        }
        Set<SubLevelLoadingTicket<?>> active = container.collectForceLoadTickets().get(subLevel);
        if (active != null && active.stream().anyMatch(tickets::contains)) {
            throw new IllegalStateException("Source Sable force-load ticket removal is incomplete");
        }
    }

    private static void restoreForceLoadTickets(
        ServerSubLevelContainer container,
        ServerSubLevel subLevel,
        List<SubLevelLoadingTicket<?>> tickets
    ) {
        for (SubLevelLoadingTicket<?> ticket : tickets) {
            addForceLoadTicketUnchecked(container, subLevel, ticket);
        }
    }

    private static void removeForceLoadTicketsBestEffort(
        ServerSubLevelContainer container,
        ServerSubLevel subLevel,
        List<SubLevelLoadingTicket<?>> tickets
    ) {
        for (SubLevelLoadingTicket<?> ticket : tickets) {
            try {
                removeForceLoadTicketUnchecked(container, subLevel, ticket);
            }
            catch (RuntimeException ignored) {
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addForceLoadTicketUnchecked(
        ServerSubLevelContainer container,
        ServerSubLevel subLevel,
        SubLevelLoadingTicket<?> ticket
    ) {
        container.addForceLoadTicket(
            subLevel, (SubLevelLoadingTicketType) ticket.type(), ticket.key()
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void removeForceLoadTicketUnchecked(
        ServerSubLevelContainer container,
        ServerSubLevel subLevel,
        SubLevelLoadingTicket<?> ticket
    ) {
        container.removeForceLoadTicket(
            subLevel, (SubLevelLoadingTicketType) ticket.type(), ticket.key()
        );
    }

    private static void cleanupStagedUnits(
        ServerSubLevelContainer destinationContainer, List<MigrationUnit> units
    ) {
        for (MigrationUnit unit : units) {
            abortClientHandoff(unit.source().getUniqueId());
            removeForceLoadTicketsBestEffort(destinationContainer, unit.destination(), unit.tickets());
            if (!unit.destination().isRemoved()) {
                destinationContainer.removeSubLevel(unit.destination(), SubLevelRemovalReason.REMOVED);
            }
        }
    }

    private static void rollbackAllEntities(List<MigrationUnit> units, ServerLevel sourceWorld) {
        for (int i = units.size() - 1; i >= 0; i--) {
            MigrationUnit unit = units.get(i);
            if (unit.movedById().isEmpty()) continue;
            if (!rollbackPlotEntities(unit.entities(), unit.movedById(), sourceWorld)) {
                LOGGER.error(
                    "Sable entity rollback for {} was incomplete",
                    unit.source().getUniqueId()
                );
            }
        }
    }

    private static void restoreExactVelocity(
        ServerSubLevel destinationSubLevel,
        Vector3d exactLinearVelocity,
        Vector3d exactAngularVelocity
    ) {
        RigidBodyHandle handle = RigidBodyHandle.of(destinationSubLevel);
        if (handle == null || !handle.isValid()) {
            throw new IllegalStateException(
                "Destination Sable physics handle unavailable for " + destinationSubLevel.getUniqueId()
            );
        }
        Vector3d currentLinear = handle.getLinearVelocity(new Vector3d());
        Vector3d currentAngular = handle.getAngularVelocity(new Vector3d());
        handle.addLinearAndAngularVelocity(
            new Vector3d(exactLinearVelocity).sub(currentLinear),
            new Vector3d(exactAngularVelocity).sub(currentAngular)
        );
    }

    private static void beginClientHandoff(
        ServerLevel sourceWorld,
        ServerSubLevelContainer destinationContainer,
        ServerSubLevel sourceSubLevel,
        ServerSubLevel destinationSubLevel,
        long plotCoordinate
    ) {
        Set<UUID> watchers = new HashSet<>();
        for (UUID playerId : sourceSubLevel.getTrackingPlayers()) {
            if (sourceWorld.getServer().getPlayerList().getPlayer(playerId) != null) {
                watchers.add(playerId);
            }
        }
        if (watchers.isEmpty()) return;

        ServerLevel destinationWorld = destinationContainer.getLevel();
        ClientHandoff handoff = new ClientHandoff(
            sourceWorld.dimension(), destinationWorld.dimension(), plotCoordinate,
            watchers, sourceWorld.getGameTime()
        );
        CLIENT_HANDOFFS.put(sourceSubLevel.getUniqueId(), handoff);
        destinationSubLevel.getTrackingPlayers().addAll(watchers);

        InvokerSubLevelTrackingSystem_SablePortalCompat tracking =
            (InvokerSubLevelTrackingSystem_SablePortalCompat) (Object) destinationContainer.trackingSystem();
        for (UUID playerId : watchers) {
            ServerPlayer player = sourceWorld.getServer().getPlayerList().getPlayer(playerId);
            if (player != null) {
                sendPreSyncedDestination(tracking, player, destinationSubLevel, handoff);
            }
        }
    }

    private static void sendPreSyncedDestination(
        InvokerSubLevelTrackingSystem_SablePortalCompat tracking,
        ServerPlayer player,
        ServerSubLevel destinationSubLevel,
        ClientHandoff handoff
    ) {
        // Record the exact connection before queueing its destination bundle. Once migration commits,
        // source retirement can follow that bundle on the same ordered connection immediately; it
        // must not wait for Sable's next tracking tick (which can lag badly under UDP/slow CI).
        handoff.preSyncedPlayers.add(player.getUUID());
        tracking.ip_sendFullSync(player, destinationSubLevel, null);
    }

    public static boolean shouldSuppressSourceRemoval(
        ServerLevel level, ServerSubLevel subLevel, SubLevelRemovalReason reason
    ) {
        if (reason != SubLevelRemovalReason.REMOVED) return false;
        ClientHandoff handoff = CLIENT_HANDOFFS.get(subLevel.getUniqueId());
        return handoff != null && handoff.sourceDimension.equals(level.dimension());
    }

    public static boolean shouldSkipDuplicateDestinationFullSync(
        ServerLevel level, ServerPlayer player, ServerSubLevel subLevel
    ) {
        ClientHandoff handoff = CLIENT_HANDOFFS.get(subLevel.getUniqueId());
        if (handoff == null || !handoff.committed
            || !handoff.destinationDimension.equals(level.dimension())) return false;

        boolean skip = handoff.preSyncedPlayers.remove(player.getUUID());
        cleanupHandoff(subLevel.getUniqueId(), handoff);
        return skip;
    }

    public static void onDestinationFullSync(
        ServerLevel level, ServerPlayer player, ServerSubLevel subLevel
    ) {
        ClientHandoff handoff = CLIENT_HANDOFFS.get(subLevel.getUniqueId());
        if (handoff == null || !handoff.destinationDimension.equals(level.dimension())) return;

        if (!handoff.committed) {
            handoff.preSyncedPlayers.add(player.getUUID());
            return;
        }

        if (handoff.pendingSourceRemoval.remove(player.getUUID())) {
            sendSourceRemoval(level, player, handoff);
        }
        cleanupHandoff(subLevel.getUniqueId(), handoff);
    }

    private static void commitClientHandoff(ServerLevel currentLevel, UUID subLevelId) {
        ClientHandoff handoff = CLIENT_HANDOFFS.get(subLevelId);
        if (handoff == null) return;
        handoff.committed = true;

        for (UUID playerId : List.copyOf(handoff.preSyncedPlayers)) {
            if (!handoff.pendingSourceRemoval.remove(playerId)) continue;
            ServerPlayer player = currentLevel.getServer().getPlayerList().getPlayer(playerId);
            if (player != null) sendSourceRemoval(currentLevel, player, handoff);
        }
        cleanupHandoff(subLevelId, handoff);
    }

    private static void abortClientHandoff(UUID subLevelId) {
        CLIENT_HANDOFFS.remove(subLevelId);
    }

    private static void cleanupHandoff(UUID subLevelId, ClientHandoff handoff) {
        if (handoff.committed
            && handoff.pendingSourceRemoval.isEmpty()
            && handoff.preSyncedPlayers.isEmpty()) {
            CLIENT_HANDOFFS.remove(subLevelId);
        }
    }

    private static void sendSourceRemoval(ServerLevel currentLevel, ServerPlayer player, ClientHandoff handoff) {
        ServerLevel sourceWorld = currentLevel.getServer().getLevel(handoff.sourceDimension);
        if (sourceWorld == null) return;
        PacketRedirection.withForceRedirect(sourceWorld, () -> player.connection.send(
            new ClientboundCustomPayloadPacket(
                new ClientboundStopTrackingSubLevelPacket(handoff.plotCoordinate)
            )
        ));
    }

    private static void expireStaleClientHandoffs(ServerLevel level) {
        Iterator<Map.Entry<UUID, ClientHandoff>> iterator = CLIENT_HANDOFFS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ClientHandoff> entry = iterator.next();
            ClientHandoff handoff = entry.getValue();
            ServerLevel sourceWorld = level.getServer().getLevel(handoff.sourceDimension);
            if (sourceWorld == null
                || sourceWorld.getGameTime() - handoff.createdGameTime <= CLIENT_HANDOFF_TIMEOUT_TICKS) {
                continue;
            }

            for (UUID playerId : List.copyOf(handoff.pendingSourceRemoval)) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
                if (player != null) sendSourceRemoval(level, player, handoff);
            }
            LOGGER.warn(
                "Timed out draining Sable handoff {}; retired {} stale source copies",
                entry.getKey(), handoff.pendingSourceRemoval.size()
            );
            iterator.remove();
        }
    }

    private static boolean isMatchingDestinationSlotFree(
        ServerSubLevelContainer container, int localPlotX, int localPlotZ
    ) {
        int sideLength = 1 << container.getLogSideLength();
        if (localPlotX < 0 || localPlotX >= sideLength || localPlotZ < 0 || localPlotZ >= sideLength) {
            return false;
        }
        return !container.getOccupancy().get(container.getIndex(localPlotX, localPlotZ));
    }

    static boolean rebasePlotSections(
        CompoundTag tag, int sourceMinSection,
        int destinationMinSection, int destinationSectionCount
    ) {
        CompoundTag chunks = tag.getCompound("plot").getCompound("chunks");
        for (String chunkKey : chunks.getAllKeys()) {
            for (String key : chunks.getCompound(chunkKey).getCompound("sections").getAllKeys()) {
                int index = Integer.parseInt(key) + sourceMinSection - destinationMinSection;
                if (index < 0 || index >= destinationSectionCount) return false;
            }
        }

        boolean verticalOriginChanged = sourceMinSection != destinationMinSection;
        for (String chunkKey : chunks.getAllKeys()) {
            CompoundTag chunk = chunks.getCompound(chunkKey);
            CompoundTag sections = chunk.getCompound("sections");
            CompoundTag rebased = new CompoundTag();
            for (String key : sections.getAllKeys()) {
                int index = Integer.parseInt(key) + sourceMinSection - destinationMinSection;
                rebased.put(Integer.toString(index), sections.get(key));
            }
            chunk.put("sections", rebased);

            // Vanilla Heightmap stores height relative to chunk.getMinBuildHeight(). Even when
            // the packed array length happens to match, reusing Overworld data in Nether (or
            // vice versa) shifts every decoded height. Missing keys make Sable prime them from
            // the newly loaded block sections, which is the correct cross-dimension behavior.
            if (verticalOriginChanged) {
                chunk.put("heightmaps", new CompoundTag());
            }
        }
        return true;
    }

    private static SubLevelData transformSerializedState(SubLevelData sourceData, Portal portal) {
        CompoundTag tag = sourceData.fullTag().copy();
        Pose3d pose = transformPose(SableNBTUtils.readPose3d(tag.getCompound("pose")), portal);
        tag.put("pose", SableNBTUtils.writePose3d(pose));
        transformVelocity(tag, "linear_velocity", portal, true);
        transformVelocity(tag, "angular_velocity", portal, false);
        return new SubLevelData(sourceData.uuid(), sourceData.bounds(), pose, sourceData.dependencies(), tag);
    }

    private static Pose3d transformPose(Pose3d source, Portal portal) {
        Pose3d pose = new Pose3d(source);
        Vec3 destinationPosition = portal.transformPoint(JOMLConversion.toMojang(pose.position()));
        pose.position().set(destinationPosition.x, destinationPosition.y, destinationPosition.z);
        DQuaternion rotation = portal.getRotation();
        if (rotation != null) {
            pose.orientation().premul(new Quaterniond(rotation.x, rotation.y, rotation.z, rotation.w));
        }
        return pose;
    }

    private static void transformVelocity(
        CompoundTag tag, String key, Portal portal, boolean includeScale
    ) {
        if (!tag.contains(key)) return;
        Vector3d velocity = SableNBTUtils.readVector3d(tag.getCompound(key));
        Vec3 transformed = includeScale
            ? portal.transformLocalVec(JOMLConversion.toMojang(velocity))
            : portal.transformLocalVecNonScale(JOMLConversion.toMojang(velocity));
        tag.put(key, SableNBTUtils.writeVector3d(JOMLConversion.toJOML(transformed)));
    }

    private static List<EntityTransfer> capturePlotEntities(
        ServerLevel sourceWorld, ServerSubLevel sourceSubLevel, Portal portal
    ) {
        List<Entity> migrationEntities = new ArrayList<>();
        Set<UUID> migrationIds = new HashSet<>();
        Set<UUID> plotResidentIds = new HashSet<>();

        EntitySectionStorage<Entity> storage = ((IEServerEntityManager)
            ((IEServerWorld) sourceWorld).ip_getEntityManager()).ip_getSectionStorage();
        for (var chunk : sourceSubLevel.getPlot().getLoadedChunks()) {
            for (Entity entity : storage.getExistingSectionsInChunk(chunk.getChunk().getPos().toLong())
                .flatMap(section -> section.getEntities()).toList()) {
                if (entity.isRemoved() || !migrationIds.add(entity.getUUID())) continue;
                migrationEntities.add(entity);
                plotResidentIds.add(entity.getUUID());
            }
        }

        for (int i = 0; i < migrationEntities.size(); i++) {
            Entity vehicle = migrationEntities.get(i);
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger.level() == sourceWorld && migrationIds.add(passenger.getUUID())) {
                    migrationEntities.add(passenger);
                }
            }
        }

        List<EntityTransfer> transfers = new ArrayList<>(migrationEntities.size());
        for (Entity entity : migrationEntities) {
            Entity vehicle = entity.getVehicle();
            UUID vehicleId = vehicle != null && migrationIds.contains(vehicle.getUUID())
                ? vehicle.getUUID() : null;
            Vec3 storedPosition = entity.position();
            Vec3 storedVelocity = entity.getDeltaMovement();
            Vec3 destinationPosition = storedPosition;
            Vec3 destinationVelocity = storedVelocity;

            boolean kickedPassenger = vehicleId != null && EntitySubLevelUtil.shouldKick(entity);
            if (kickedPassenger) {
                Vec3 logicalPosition = plotResidentIds.contains(entity.getUUID())
                    ? EntityRidingSubLevelVehicleHelper.kickRidingEntity(entity, sourceSubLevel)
                    : storedPosition;
                destinationPosition = portal.transformPoint(logicalPosition);
                destinationVelocity = portal.transformLocalVec(storedVelocity);
            }

            transfers.add(new EntityTransfer(
                entity, storedPosition, storedVelocity,
                destinationPosition, destinationVelocity, vehicleId
            ));
        }
        return transfers;
    }

    private static void transferPlotEntities(
        List<EntityTransfer> transfers, ServerLevel destinationWorld, Map<UUID, Entity> movedById
    ) {
        detachEntityGraph(transfers);
        for (EntityTransfer transfer : transfers) {
            Entity moved = ServerTeleportationManager.teleportEntityGeneral(
                transfer.entity(), transfer.destinationPosition(), destinationWorld
            );
            if (moved == null || moved.level() != destinationWorld) {
                throw new IllegalStateException(
                    "Entity failed cross-dimension transfer: " + transfer.entity()
                );
            }
            moved.setDeltaMovement(transfer.destinationVelocity());
            movedById.put(transfer.entity().getUUID(), moved);
        }
        restoreRidingRelations(transfers, movedById, true);
    }

    private static boolean rollbackPlotEntities(
        List<EntityTransfer> transfers, Map<UUID, Entity> movedById, ServerLevel sourceWorld
    ) {
        Map<UUID, Entity> sourceById = new HashMap<>();
        try {
            for (EntityTransfer transfer : transfers) {
                Entity entity = movedById.getOrDefault(transfer.entity().getUUID(), transfer.entity());
                entity = ServerTeleportationManager.teleportEntityGeneral(
                    entity, transfer.storedPosition(), sourceWorld
                );
                if (entity == null || entity.level() != sourceWorld) return false;
                entity.setDeltaMovement(transfer.storedVelocity());
                sourceById.put(transfer.entity().getUUID(), entity);
            }
            restoreRidingRelations(transfers, sourceById, false);
            return true;
        }
        catch (RuntimeException rollbackFailure) {
            LOGGER.error("Failed rolling back partial Sable cross-dimension entity migration", rollbackFailure);
            return false;
        }
    }

    private static void detachEntityGraph(List<EntityTransfer> transfers) {
        for (EntityTransfer transfer : transfers) {
            transfer.entity().stopRiding();
            transfer.entity().ejectPassengers();
        }
    }

    private static void restoreRidingRelations(
        List<EntityTransfer> transfers, Map<UUID, Entity> entitiesById, boolean failOnMissingRelation
    ) {
        for (EntityTransfer transfer : transfers) {
            if (transfer.vehicleId() == null) continue;
            Entity passenger = entitiesById.get(transfer.entity().getUUID());
            Entity vehicle = entitiesById.get(transfer.vehicleId());
            boolean restored = passenger != null && vehicle != null && passenger.startRiding(vehicle, true);
            if (!restored && failOnMissingRelation) {
                throw new IllegalStateException(
                    "Failed to restore Sable riding relation " + transfer.entity().getUUID()
                        + " -> " + transfer.vehicleId()
                );
            }
        }
    }

    private record CrossingSample(ResourceKey<Level> dimension, Vec3 anchor, long gameTime) {}

    private record HandoffGuard(
        ResourceKey<Level> sourceDimension,
        ResourceKey<Level> destinationDimension,
        Vec3 destinationPlane,
        Vec3 destinationDirection
    ) {}

    private static final class ClientHandoff {
        private final ResourceKey<Level> sourceDimension;
        private final ResourceKey<Level> destinationDimension;
        private final long plotCoordinate;
        private final Set<UUID> pendingSourceRemoval;
        private final Set<UUID> preSyncedPlayers = new HashSet<>();
        private final long createdGameTime;
        private boolean committed;

        private ClientHandoff(
            ResourceKey<Level> sourceDimension,
            ResourceKey<Level> destinationDimension,
            long plotCoordinate,
            Set<UUID> watchers,
            long createdGameTime
        ) {
            this.sourceDimension = sourceDimension;
            this.destinationDimension = destinationDimension;
            this.plotCoordinate = plotCoordinate;
            this.pendingSourceRemoval = new HashSet<>(watchers);
            this.createdGameTime = createdGameTime;
        }
    }

    private record MigrationUnit(
        ServerSubLevel source,
        ServerSubLevel destination,
        int localPlotX,
        int localPlotZ,
        List<EntityTransfer> entities,
        List<SubLevelLoadingTicket<?>> tickets,
        Map<UUID, Entity> movedById
    ) {}

    private record EntityTransfer(
        Entity entity,
        Vec3 storedPosition,
        Vec3 storedVelocity,
        Vec3 destinationPosition,
        Vec3 destinationVelocity,
        UUID vehicleId
    ) {}
}
