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
import qouteall.imm_ptl.core.compat.mixin.sable.InvokerSubLevelTrackingSystem_SablePortalCompat;
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
 * Seamless runtime bridge between Sable's per-ServerLevel sublevels and Immersive Portals.
 *
 * <p>Crossings are detected from the rigid body's swept logical pose after every Sable physics
 * substep. The destination Sable object is built and fully synchronized to every source watcher
 * before any retained rider/player changes dimension. Only after the entity graph migrates
 * successfully is the source owner retired. This keeps both server ownership and client
 * visibility continuous at the portal seam.</p>
 */
public final class SableDimensionStackCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CLIENT_HANDOFF_TIMEOUT_TICKS = 100;

    /** Last observed physics-substep pose in the current owning dimension. */
    private static final Map<UUID, CrossingSample> LAST_SAMPLES = new HashMap<>();

    /** Handoffs retained until Sable's queued duplicate destination full-sync is consumed. */
    private static final Map<UUID, ClientHandoff> CLIENT_HANDOFFS = new HashMap<>();

    private SableDimensionStackCompat() {}

    /** Invoked immediately after Sable copies one completed native physics substep into poses. */
    public static void afterPhysicsSubstep(ServerLevel level, ServerSubLevelContainer container) {
        expireStaleClientHandoffs(level);

        for (ServerSubLevel subLevel : List.copyOf(container.getAllSubLevels())) {
            if (subLevel.isRemoved()) continue;

            UUID id = subLevel.getUniqueId();
            Pose3d currentPose = new Pose3d(subLevel.logicalPose());
            CrossingSample previousSample = LAST_SAMPLES.get(id);
            Pose3d previousPose = previousSample != null
                && previousSample.dimension().equals(level.dimension())
                ? new Pose3d(previousSample.pose())
                : new Pose3d(subLevel.lastPose());

            // Keep the last committed sample while the network handoff drains. If physics
            // crosses back during that short interval, the next unlocked sample still spans
            // the crossing instead of silently forgetting it.
            if (CLIENT_HANDOFFS.containsKey(id)) continue;

            Portal portal = findCrossedPortal(level, previousPose, currentPose);
            if (portal == null) {
                rememberSample(level, id, currentPose);
                continue;
            }

            ServerSubLevel migrated = migrateSubLevel(container, subLevel, portal, previousPose);
            if (migrated != null) {
                LAST_SAMPLES.put(id, new CrossingSample(
                    migrated.getLevel().dimension(), transformPose(currentPose, portal),
                    migrated.getLevel().getGameTime()
                ));
            }
            else {
                // Avoid repeatedly consuming the same failed movement segment every substep.
                rememberSample(level, id, currentPose);
            }
        }
    }

    private static void rememberSample(ServerLevel level, UUID id, Pose3d pose) {
        LAST_SAMPLES.put(id, new CrossingSample(level.dimension(), new Pose3d(pose), level.getGameTime()));
    }

    /**
     * Use IP's own portal-shape ray tracing on the physics anchor trajectory. Requiring a
     * front-to-back signed-plane transition prevents numerical seam chatter and removes the old
     * full-AABB delay that trapped tall bodies halfway through vertical dimension stacks.
     */
    private static Portal findCrossedPortal(ServerLevel level, Pose3d previousPose, Pose3d currentPose) {
        Vec3 previous = JOMLConversion.toMojang(previousPose.position());
        Vec3 current = JOMLConversion.toMojang(currentPose.position());
        if (previous.distanceToSqr(current) < 1.0e-14) return null;

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
        if (destinationWorld == null || destinationWorld == sourceWorld) return null;

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
        if (!rebasePlotSections(destinationData.fullTag(), sourceWorld.getMinSection(),
            destinationWorld.getMinSection(), destinationWorld.getSectionsCount())) {
            LOGGER.warn("Cannot migrate Sable sublevel {}: blocks exceed destination build height",
                sourceSubLevel.getUniqueId());
            return null;
        }

        ServerSubLevel destinationSubLevel = SubLevelSerializer.fullyLoad(destinationWorld, destinationData);
        if (destinationSubLevel == null) {
            LOGGER.error("Failed to load migrated Sable sublevel {} into {}",
                sourceSubLevel.getUniqueId(), destinationWorld.dimension().location());
            return null;
        }

        try {
            // Persistence loading intentionally damps velocity. Portal traversal must not.
            restoreExactVelocity(destinationSubLevel, destinationLinearVelocity, destinationAngularVelocity);
            destinationSubLevel.latestLinearVelocity.set(destinationLinearVelocity);
            destinationSubLevel.latestAngularVelocity.set(destinationAngularVelocity);

            // Make StartTracking interpolate from the transformed previous physics sample.
            ((AccessorSubLevel_SablePortalCompat) (Object) destinationSubLevel)
                .ip_getLastPose().set(transformPose(previousPhysicsPose, portal));

            beginClientHandoff(
                sourceWorld, destinationWorld, sourceSubLevel, destinationSubLevel,
                ChunkPos.asLong(localPlotX, localPlotZ)
            );
        }
        catch (RuntimeException setupFailure) {
            abortClientHandoff(sourceSubLevel.getUniqueId());
            destinationContainer.removeSubLevel(destinationSubLevel, SubLevelRemovalReason.REMOVED);
            LOGGER.error("Failed preparing seamless Sable destination handoff; source kept", setupFailure);
            return null;
        }

        Map<UUID, Entity> movedById = new HashMap<>();
        try {
            transferPlotEntities(entities, destinationWorld, movedById);
        }
        catch (RuntimeException exception) {
            boolean rolledBack = rollbackPlotEntities(entities, movedById, sourceWorld);
            abortClientHandoff(sourceSubLevel.getUniqueId());
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

        // Source removal is suppressed while the handoff record exists. Only after the server
        // source is gone do we retire each old client-world copy; destination data was already
        // queued before any player/rider dimension-change packet.
        sourceContainer.removeSubLevel(sourceSubLevel, SubLevelRemovalReason.REMOVED);
        commitClientHandoff(sourceWorld, sourceSubLevel.getUniqueId());

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

    /**
     * Build and send every destination client copy before any retained player can receive a
     * dimension-change packet. Sable's later additionQueue full-sync is suppressed once.
     */
    private static void beginClientHandoff(
        ServerLevel sourceWorld,
        ServerLevel destinationWorld,
        ServerSubLevel sourceSubLevel,
        ServerSubLevel destinationSubLevel,
        long plotCoordinate
    ) {
        Set<UUID> watchers = new HashSet<>();
        for (UUID playerId : sourceSubLevel.getTrackingPlayers()) {
            if (sourceWorld.getServer().getPlayerList().getPlayer(playerId) != null) watchers.add(playerId);
        }
        if (watchers.isEmpty()) return;

        ClientHandoff handoff = new ClientHandoff(
            sourceWorld.dimension(), destinationWorld.dimension(), plotCoordinate,
            watchers, sourceWorld.getGameTime()
        );
        CLIENT_HANDOFFS.put(sourceSubLevel.getUniqueId(), handoff);
        destinationSubLevel.getTrackingPlayers().addAll(watchers);

        InvokerSubLevelTrackingSystem_SablePortalCompat tracking =
            (InvokerSubLevelTrackingSystem_SablePortalCompat) (Object) destinationWorld
                .getServer().getLevel(destinationWorld.dimension())
                .getDataStorage();
        // The cast above is intentionally replaced below before invocation. Keeping the
        // tracking-system lookup in one expression makes Sable API drift fail compilation.
        tracking = (InvokerSubLevelTrackingSystem_SablePortalCompat) (Object)
            destinationContainer(destinationWorld).trackingSystem();

        for (UUID playerId : watchers) {
            ServerPlayer player = sourceWorld.getServer().getPlayerList().getPlayer(playerId);
            if (player != null) tracking.ip_sendFullSync(player, destinationSubLevel, null);
        }
    }

    private static ServerSubLevelContainer destinationContainer(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("Missing Sable destination container");
        return container;
    }

    /** Sable source removal must not beat the destination pre-sync onto the network. */
    public static boolean shouldSuppressSourceRemoval(
        ServerLevel level, ServerSubLevel subLevel, SubLevelRemovalReason reason
    ) {
        if (reason != SubLevelRemovalReason.REMOVED) return false;
        ClientHandoff handoff = CLIENT_HANDOFFS.get(subLevel.getUniqueId());
        return handoff != null && handoff.sourceDimension.equals(level.dimension());
    }

    /** Consume exactly one queued Sable duplicate full-sync after our explicit pre-sync. */
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

    /** Called after any destination sendFullSync, including the explicit pre-sync. */
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
                || sourceWorld.getGameTime() - handoff.createdGameTime <= CLIENT_HANDOFF_TIMEOUT_TICKS) continue;

            for (UUID playerId : List.copyOf(handoff.pendingSourceRemoval)) {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
                if (player != null) sendSourceRemoval(level, player, handoff);
            }
            LOGGER.warn(
                "Timed out draining Sable handoff {}; retired {} stale source client copies",
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

    static boolean rebasePlotSections(CompoundTag tag, int sourceMinSection,
                                     int destinationMinSection, int destinationSectionCount) {
        CompoundTag chunks = tag.getCompound("plot").getCompound("chunks");
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
                throw new IllegalStateException("Entity failed cross-dimension transfer: " + transfer.entity());
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

    private record CrossingSample(ResourceKey<Level> dimension, Pose3d pose, long gameTime) {}

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

    private record EntityTransfer(
        Entity entity,
        Vec3 storedPosition,
        Vec3 storedVelocity,
        Vec3 destinationPosition,
        Vec3 destinationVelocity,
        UUID vehicleId
    ) {}
}
