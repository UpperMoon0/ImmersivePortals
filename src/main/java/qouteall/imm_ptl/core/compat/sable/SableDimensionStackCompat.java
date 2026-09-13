package qouteall.imm_ptl.core.compat.sable;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinhelpers.entity.entity_riding_sub_level_vehicle.EntityRidingSubLevelVehicleHelper;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
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
import org.joml.Vector3d;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin.sable.AccessorSubLevel_SablePortalCompat;
import qouteall.imm_ptl.core.ducks.IEServerEntityManager;
import qouteall.imm_ptl.core.ducks.IEServerWorld;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bridges Sable's level-bound sublevels through Immersive Portals.
 *
 * <p>Sable owns one physics pipeline and one plot container per {@link ServerLevel}, so a
 * rigid body cannot simply keep integrating past a cross-dimension portal while remaining
 * attached to its old world. The compatibility layer samples every completed Sable physics
 * substep, detects a swept portal crossing of the body's logical pose, reconstructs the plot
 * in the destination pipeline, restores the exact live rigid-body state, migrates retained
 * entities and riders transactionally, and then retires the source owner.</p>
 *
 * <p>The client handoff is ordered destination-first. Players already tracking the source
 * sublevel are seeded into the destination tracking set. The normal source stop packet is
 * suppressed while the handoff is pending; after each destination full-sync is sent, the old
 * source copy is explicitly retired for that player. This removes the old stop/start visibility
 * gap while keeping one authoritative server owner.</p>
 */
public final class SableDimensionStackCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CLIENT_HANDOFF_TIMEOUT_TICKS = 100;

    /** Last physics-substep pose for each live logical Sable body. */
    private static final Map<UUID, CrossingSample> LAST_SAMPLES = new HashMap<>();

    /** Destination-first client handoffs that have not yet retired every old client copy. */
    private static final Map<UUID, ClientHandoff> CLIENT_HANDOFFS = new HashMap<>();

    private SableDimensionStackCompat() {}

    /**
     * Called after every Sable physics substep, immediately after Sable has copied the native
     * pipeline pose back into {@link ServerSubLevel#logicalPose()}.
     */
    public static void afterPhysicsSubstep(ServerLevel level, ServerSubLevelContainer container) {
        expireStaleClientHandoffs(level);

        List<ServerSubLevel> snapshot = List.copyOf(container.getAllSubLevels());
        for (ServerSubLevel subLevel : snapshot) {
            if (subLevel.isRemoved()) {
                continue;
            }

            UUID id = subLevel.getUniqueId();
            Pose3d currentPose = new Pose3d(subLevel.logicalPose());
            CrossingSample previousSample = LAST_SAMPLES.get(id);
            Pose3d previousPose = previousSample != null
                && previousSample.dimension().equals(level.dimension())
                ? new Pose3d(previousSample.pose())
                : new Pose3d(subLevel.lastPose());

            // Do not chain another ownership change while the previous destination-first
            // network handoff is still being acknowledged. Physics continues normally in the
            // new owner; this only prevents a pathological portal loop from outrunning sync.
            if (CLIENT_HANDOFFS.containsKey(id)) {
                rememberSample(level, id, currentPose);
                continue;
            }

            Portal portal = findCrossedPortal(level, previousPose, currentPose);
            if (portal == null) {
                rememberSample(level, id, currentPose);
                continue;
            }

            ServerSubLevel migrated = migrateSubLevel(
                container, subLevel, portal, previousPose
            );
            if (migrated != null) {
                LAST_SAMPLES.put(id, new CrossingSample(
                    migrated.getLevel().dimension(), transformPose(currentPose, portal),
                    migrated.getLevel().getGameTime()
                ));
            }
            else {
                // Migration failure must not make the detector repeatedly rediscover the same
                // already-consumed segment every physics substep.
                rememberSample(level, id, currentPose);
            }
        }
    }

    private static void rememberSample(ServerLevel level, UUID id, Pose3d pose) {
        LAST_SAMPLES.put(id, new CrossingSample(level.dimension(), new Pose3d(pose), level.getGameTime()));
    }

    /**
     * Detect a real front-to-back crossing of the body's logical anchor through the portal
     * aperture. Unlike the old full-AABB rule this is independent of body height and cannot
     * leave a tall gravity-driven body waiting half inside each stacked dimension.
     */
    private static Portal findCrossedPortal(ServerLevel level, Pose3d previousPose, Pose3d currentPose) {
        Vec3 previous = JOMLConversion.toMojang(previousPose.position());
        Vec3 current = JOMLConversion.toMojang(currentPose.position());
        if (previous.distanceToSqr(current) < 1.0e-14) {
            return null;
        }

        return PortalUtils.raytracePortals(
            level, previous, current, true,
            portal -> portal.isTeleportable()
                && !portal.hasScaling()
                && !portal.getDestDim().equals(level.dimension())
                && portal.getDistanceToPlane(previous) > 0.0
                && portal.getDistanceToPlane(current) <= 0.0
        ).map(Pair::getFirst).orElse(null);
    }

    private static ServerSubLevel migrateSubLevel(
        ServerSubLevelContainer sourceContainer,
        ServerSubLevel sourceSubLevel,
        Portal portal,
        Pose3d previousPhysicsPose
    ) {
        ServerLevel sourceWorld = sourceContainer.getLevel();
        ServerLevel destinationWorld = sourceWorld.getServer().getLevel(portal.getDestDim());
        if (destinationWorld == null || destinationWorld == sourceWorld) {
            return null;
        }

        ServerSubLevelContainer destinationContainer = SubLevelContainer.getContainer(destinationWorld);
        if (destinationContainer == null) {
            LOGGER.error(
                "Cannot migrate Sable sublevel {} through portal: destination {} has no Sable container",
                sourceSubLevel.getUniqueId(), portal.getDestDim().location()
            );
            return null;
        }

        int localPlotX = sourceSubLevel.getPlot().plotPos.x - sourceContainer.getOrigin().x;
        int localPlotZ = sourceSubLevel.getPlot().plotPos.z - sourceContainer.getOrigin().y;
        if (!isMatchingDestinationSlotFree(destinationContainer, localPlotX, localPlotZ)) {
            LOGGER.warn(
                "Cannot migrate Sable sublevel {} from {} to {}: matching destination plot slot {},{} is occupied",
                sourceSubLevel.getUniqueId(), sourceWorld.dimension().location(),
                destinationWorld.dimension().location(), localPlotX, localPlotZ
            );
            return null;
        }

        RigidBodyHandle sourceHandle = RigidBodyHandle.of(sourceSubLevel);
        if (sourceHandle == null || !sourceHandle.isValid()) {
            LOGGER.error("Cannot migrate Sable sublevel {}: source physics handle is unavailable",
                sourceSubLevel.getUniqueId());
            return null;
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
        SubLevelData sourceData = SubLevelSerializer.toData(sourceSubLevel, List.of());
        SubLevelData destinationData = transformSerializedState(sourceData, portal);

        // Sable stores section array indices relative to the owning level's minimum build
        // height. Preserve absolute plot-space Y when worlds use different build ranges.
        if (!rebasePlotSections(destinationData.fullTag(), sourceWorld.getMinSection(),
            destinationWorld.getMinSection(), destinationWorld.getSectionsCount())) {
            LOGGER.warn("Cannot migrate Sable sublevel {}: blocks exceed destination build height",
                sourceSubLevel.getUniqueId());
            return null;
        }

        ServerSubLevel destinationSubLevel = SubLevelSerializer.fullyLoad(destinationWorld, destinationData);
        if (destinationSubLevel == null) {
            LOGGER.error(
                "Failed to load migrated Sable sublevel {} into {}",
                sourceSubLevel.getUniqueId(), destinationWorld.dimension().location()
            );
            return null;
        }

        // SubLevelSerializer is a persistence API and intentionally applies
        // SableConfig.VELOCITY_RETAINED_ON_LOAD (0.9 by default). Portal traversal is not a
        // persistence load: overwrite that approximation with the exact live physics state.
        restoreExactVelocity(destinationSubLevel, destinationLinearVelocity, destinationAngularVelocity);
        destinationSubLevel.latestLinearVelocity.set(destinationLinearVelocity);
        destinationSubLevel.latestAngularVelocity.set(destinationAngularVelocity);

        // Preserve the previous physics-substep pose so the destination StartTracking packet
        // initializes interpolation from the transformed pre-crossing state rather than a
        // freshly-created stationary pose.
        Pose3d transformedPreviousPose = transformPose(previousPhysicsPose, portal);
        ((AccessorSubLevel_SablePortalCompat) (Object) destinationSubLevel)
            .ip_getLastPose().set(transformedPreviousPose);

        Map<UUID, Entity> movedById = new HashMap<>();
        try {
            transferPlotEntities(entities, destinationWorld, movedById);
        }
        catch (RuntimeException exception) {
            boolean rolledBack = rollbackPlotEntities(entities, movedById, sourceWorld);
            if (rolledBack) {
                destinationContainer.removeSubLevel(destinationSubLevel, SubLevelRemovalReason.REMOVED);
            }
            else {
                LOGGER.error(
                    "Sable entity rollback for sublevel {} was incomplete; keeping both sublevel copies to avoid deleting retained entities",
                    sourceSubLevel.getUniqueId()
                );
            }
            LOGGER.error(
                "Failed to migrate entities with Sable sublevel {} from {} to {}; source copy kept",
                sourceSubLevel.getUniqueId(), sourceWorld.dimension().location(),
                destinationWorld.dimension().location(), exception
            );
            return null;
        }

        beginClientHandoff(
            sourceWorld, destinationWorld, sourceSubLevel, destinationSubLevel,
            ChunkPos.asLong(localPlotX, localPlotZ)
        );

        sourceContainer.removeSubLevel(sourceSubLevel, SubLevelRemovalReason.REMOVED);
        LOGGER.debug(
            "Migrated Sable sublevel {} from {} to {} through portal {}",
            destinationSubLevel.getUniqueId(), sourceWorld.dimension().location(),
            destinationWorld.dimension().location(), portal.getUUID()
        );
        return destinationSubLevel;
    }

    private static void restoreExactVelocity(
        ServerSubLevel destinationSubLevel,
        Vector3d exactLinearVelocity,
        Vector3d exactAngularVelocity
    ) {
        RigidBodyHandle destinationHandle = RigidBodyHandle.of(destinationSubLevel);
        if (destinationHandle == null || !destinationHandle.isValid()) {
            throw new IllegalStateException(
                "Destination Sable physics handle unavailable for " + destinationSubLevel.getUniqueId()
            );
        }
        Vector3d currentLinear = destinationHandle.getLinearVelocity(new Vector3d());
        Vector3d currentAngular = destinationHandle.getAngularVelocity(new Vector3d());
        destinationHandle.addLinearAndAngularVelocity(
            new Vector3d(exactLinearVelocity).sub(currentLinear),
            new Vector3d(exactAngularVelocity).sub(currentAngular)
        );
    }

    private static void beginClientHandoff(
        ServerLevel sourceWorld,
        ServerLevel destinationWorld,
        ServerSubLevel sourceSubLevel,
        ServerSubLevel destinationSubLevel,
        long sourcePlotCoordinate
    ) {
        Set<UUID> pendingPlayers = new HashSet<>();
        for (UUID playerId : sourceSubLevel.getTrackingPlayers()) {
            if (sourceWorld.getServer().getPlayerList().getPlayer(playerId) != null) {
                pendingPlayers.add(playerId);
            }
        }
        if (pendingPlayers.isEmpty()) {
            return;
        }

        // Seed the destination tracker before the source is retired. Its normal additionQueue
        // full-sync will therefore reach every player who could currently see the source copy.
        destinationSubLevel.getTrackingPlayers().addAll(pendingPlayers);
        CLIENT_HANDOFFS.put(sourceSubLevel.getUniqueId(), new ClientHandoff(
            sourceWorld.dimension(), destinationWorld.dimension(), sourcePlotCoordinate,
            pendingPlayers, sourceWorld.getGameTime()
        ));
    }

    /** Called by the Sable tracking mixin before its normal source removal broadcast. */
    public static boolean shouldSuppressSourceRemoval(
        ServerLevel level, ServerSubLevel subLevel, SubLevelRemovalReason reason
    ) {
        if (reason != SubLevelRemovalReason.REMOVED) {
            return false;
        }
        ClientHandoff handoff = CLIENT_HANDOFFS.get(subLevel.getUniqueId());
        return handoff != null && handoff.sourceDimension().equals(level.dimension());
    }

    /**
     * Called after Sable sends the destination full-sync to one player. Retire that player's
     * old source-world copy only now, guaranteeing destination-before-source packet order.
     */
    public static void onDestinationFullSync(
        ServerLevel level, ServerPlayer player, ServerSubLevel subLevel
    ) {
        ClientHandoff handoff = CLIENT_HANDOFFS.get(subLevel.getUniqueId());
        if (handoff == null || !handoff.destinationDimension().equals(level.dimension())) {
            return;
        }
        if (!handoff.pendingPlayers().remove(player.getUUID())) {
            return;
        }

        sendSourceRemoval(level, player, handoff);
        if (handoff.pendingPlayers().isEmpty()) {
            CLIENT_HANDOFFS.remove(subLevel.getUniqueId());
        }
    }

    private static void sendSourceRemoval(ServerLevel currentLevel, ServerPlayer player, ClientHandoff handoff) {
        ServerLevel sourceWorld = currentLevel.getServer().getLevel(handoff.sourceDimension());
        if (sourceWorld == null) {
            return;
        }
        PacketRedirection.withForceRedirect(sourceWorld, () -> player.connection.send(
            new ClientboundCustomPayloadPacket(
                new ClientboundStopTrackingSubLevelPacket(handoff.sourcePlotCoordinate())
            )
        ));
    }

    private static void expireStaleClientHandoffs(ServerLevel level) {
        Iterator<Map.Entry<UUID, ClientHandoff>> iterator = CLIENT_HANDOFFS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ClientHandoff> entry = iterator.next();
            ClientHandoff handoff = entry.getValue();
            ServerLevel sourceWorld = level.getServer().getLevel(handoff.sourceDimension());
            if (sourceWorld == null
                || sourceWorld.getGameTime() - handoff.createdGameTime() <= CLIENT_HANDOFF_TIMEOUT_TICKS) {
                continue;
            }

            for (UUID playerId : List.copyOf(handoff.pendingPlayers())) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
                if (player != null) {
                    sendSourceRemoval(level, player, handoff);
                }
            }
            LOGGER.warn(
                "Timed out waiting for destination Sable full-sync for {}; retired {} stale source client copies",
                entry.getKey(), handoff.pendingPlayers().size()
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

    static boolean rebasePlotSections(CompoundTag tag, int sourceMinSection,
                                     int destinationMinSection, int destinationSectionCount) {
        CompoundTag chunks = tag.getCompound("plot").getCompound("chunks");
        // Validate the entire payload before mutating any section map.
        for (String chunkKey : chunks.getAllKeys()) {
            for (String key : chunks.getCompound(chunkKey).getCompound("sections").getAllKeys()) {
                int index = Integer.parseInt(key) + sourceMinSection - destinationMinSection;
                if (index < 0 || index >= destinationSectionCount) return false;
            }
        }
        for (String chunkKey : chunks.getAllKeys()) {
            CompoundTag chunk = chunks.getCompound(chunkKey);
            CompoundTag sections = chunk.getCompound("sections");
            CompoundTag rebased = new CompoundTag();
            for (String key : sections.getAllKeys()) {
                int index = Integer.parseInt(key) + sourceMinSection - destinationMinSection;
                rebased.put(Integer.toString(index), sections.get(key));
            }
            chunk.put("sections", rebased);
        }
        return true;
    }

    private static SubLevelData transformSerializedState(SubLevelData sourceData, Portal portal) {
        CompoundTag tag = sourceData.fullTag().copy();
        Pose3d pose = transformPose(SableNBTUtils.readPose3d(tag.getCompound("pose")), portal);
        tag.put("pose", SableNBTUtils.writePose3d(pose));

        // Keep the serialized fallback coherent even though restoreExactVelocity() replaces
        // Sable's persistence-load damping with the exact live values after construction.
        transformVelocity(tag, "linear_velocity", portal, true);
        transformVelocity(tag, "angular_velocity", portal, false);

        return new SubLevelData(
            sourceData.uuid(), sourceData.bounds(), pose, sourceData.dependencies(), tag
        );
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
        if (!tag.contains(key)) {
            return;
        }

        Vector3d velocity = SableNBTUtils.readVector3d(tag.getCompound(key));
        Vec3 transformed = includeScale
            ? portal.transformLocalVec(JOMLConversion.toMojang(velocity))
            : portal.transformLocalVecNonScale(JOMLConversion.toMojang(velocity));
        tag.put(key, SableNBTUtils.writeVector3d(JOMLConversion.toJOML(transformed)));
    }

    private static List<EntityTransfer> capturePlotEntities(
        ServerLevel sourceWorld,
        ServerSubLevel sourceSubLevel,
        Portal portal
    ) {
        List<Entity> migrationEntities = new ArrayList<>();
        Set<UUID> migrationIds = new HashSet<>();
        Set<UUID> plotResidentIds = new HashSet<>();

        // Plot chunks are not ordinary player-visible chunks. After a dimension transfer
        // their entities can be in HIDDEN sections, absent from getAllEntities(). Read the
        // authoritative PersistentEntitySectionManager storage, matching Sable's own plot
        // entity removal path.
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

        // Sable deliberately kicks ordinary riders of plot-resident vehicles into logical
        // world space. Follow every passenger edge so the vehicle and its complete rider graph
        // cross as one transaction.
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
                ? vehicle.getUUID()
                : null;

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
        List<EntityTransfer> transfers,
        ServerLevel destinationWorld,
        Map<UUID, Entity> movedById
    ) {
        detachEntityGraph(transfers);

        for (EntityTransfer transfer : transfers) {
            Entity moved = ServerTeleportationManager.teleportEntityGeneral(
                transfer.entity(), transfer.destinationPosition(), destinationWorld
            );
            if (moved == null || moved.level() != destinationWorld) {
                throw new IllegalStateException("Entity failed cross-dimension transfer: " + transfer.entity());
            }
            moved.setDeltaMovement(transfer.destinationVelocity());
            movedById.put(transfer.entity().getUUID(), moved);
        }

        restoreRidingRelations(transfers, movedById, true);
    }

    private static boolean rollbackPlotEntities(
        List<EntityTransfer> transfers,
        Map<UUID, Entity> movedById,
        ServerLevel sourceWorld
    ) {
        Map<UUID, Entity> sourceById = new HashMap<>();
        try {
            for (EntityTransfer transfer : transfers) {
                Entity entity = movedById.get(transfer.entity().getUUID());
                if (entity == null) {
                    entity = transfer.entity();
                }
                entity = ServerTeleportationManager.teleportEntityGeneral(
                    entity, transfer.storedPosition(), sourceWorld
                );
                if (entity == null || entity.level() != sourceWorld) {
                    return false;
                }
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
        // Player dimension changes have special vehicle handling in IP. Detach first so each
        // migrated entity is transferred exactly once, then restore the original graph.
        for (EntityTransfer transfer : transfers) {
            transfer.entity().stopRiding();
            transfer.entity().ejectPassengers();
        }
    }

    private static void restoreRidingRelations(
        List<EntityTransfer> transfers,
        Map<UUID, Entity> entitiesById,
        boolean failOnMissingRelation
    ) {
        for (EntityTransfer transfer : transfers) {
            if (transfer.vehicleId() == null) {
                continue;
            }
            Entity passenger = entitiesById.get(transfer.entity().getUUID());
            Entity vehicle = entitiesById.get(transfer.vehicleId());
            boolean restored = passenger != null
                && vehicle != null
                && passenger.startRiding(vehicle, true);
            if (!restored && failOnMissingRelation) {
                throw new IllegalStateException(
                    "Failed to restore Sable plot riding relation " + transfer.entity().getUUID()
                        + " -> " + transfer.vehicleId()
                );
            }
        }
    }

    private record CrossingSample(ResourceKey<Level> dimension, Pose3d pose, long gameTime) {}

    private record ClientHandoff(
        ResourceKey<Level> sourceDimension,
        ResourceKey<Level> destinationDimension,
        long sourcePlotCoordinate,
        Set<UUID> pendingPlayers,
        long createdGameTime
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
