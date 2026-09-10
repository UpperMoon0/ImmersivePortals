package qouteall.imm_ptl.core.compat.sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinhelpers.entity.entity_riding_sub_level_vehicle.EntityRidingSubLevelVehicleHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bridges Sable's level-bound sublevels across Immersive Portals dimension stacks.
 *
 * <p>Sable deliberately binds every {@link ServerSubLevel} and plot container to one
 * {@link ServerLevel}. Moving a rigid body's pose across a vertical dimension-stack
 * portal therefore cannot change the level that owns its chunks, physics body, or
 * retained entities. This bridge detects that crossing after Sable has finished its
 * physics tick, recreates the sublevel in the destination level from Sable's public
 * serializer, moves plot-resident entities plus their attached riders to the destination,
 * and only then removes the source copy.</p>
 *
 * <p>The destination uses the same plot slot as the source. Sable's serialized block
 * entity/tick payloads contain plot-space positions, so silently relocating the payload
 * to a different slot would corrupt state. If that slot is already occupied in the
 * destination level, migration is refused rather than risking world corruption.</p>
 */
public final class SableDimensionStackCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SableDimensionStackCompat() {}

    public static void afterPhysicsTick(ServerLevel level, ServerSubLevelContainer container) {
        List<ServerSubLevel> snapshot = List.copyOf(container.getAllSubLevels());
        for (ServerSubLevel subLevel : snapshot) {
            if (subLevel.isRemoved()) {
                continue;
            }

            VerticalConnectingPortal portal = findCrossedStackPortal(level, subLevel);
            if (portal != null) {
                migrateSubLevel(container, subLevel, portal);
            }
        }
    }

    private static VerticalConnectingPortal findCrossedStackPortal(
        ServerLevel level, ServerSubLevel subLevel
    ) {
        Vec3 previous = JOMLConversion.toMojang(subLevel.lastPose().position());
        Vec3 current = JOMLConversion.toMojang(subLevel.logicalPose().position());

        VerticalConnectingPortal floor = VerticalConnectingPortal.getConnectingPortal(
            level, VerticalConnectingPortal.ConnectorType.floor
        );
        if (floor != null && floor.isMovedThroughPortal(previous, current)) {
            return floor;
        }

        VerticalConnectingPortal ceiling = VerticalConnectingPortal.getConnectingPortal(
            level, VerticalConnectingPortal.ConnectorType.ceil
        );
        if (ceiling != null && ceiling.isMovedThroughPortal(previous, current)) {
            return ceiling;
        }

        return null;
    }

    private static void migrateSubLevel(
        ServerSubLevelContainer sourceContainer,
        ServerSubLevel sourceSubLevel,
        VerticalConnectingPortal portal
    ) {
        ServerLevel sourceWorld = sourceContainer.getLevel();
        ServerLevel destinationWorld = sourceWorld.getServer().getLevel(portal.getDestDim());
        if (destinationWorld == null || destinationWorld == sourceWorld) {
            return;
        }

        ServerSubLevelContainer destinationContainer = SubLevelContainer.getContainer(destinationWorld);
        if (destinationContainer == null) {
            LOGGER.error(
                "Cannot migrate Sable sublevel {} through dimension stack: destination {} has no Sable container",
                sourceSubLevel.getUniqueId(), portal.getDestDim().location()
            );
            return;
        }

        int localPlotX = sourceSubLevel.getPlot().plotPos.x - sourceContainer.getOrigin().x;
        int localPlotZ = sourceSubLevel.getPlot().plotPos.z - sourceContainer.getOrigin().y;
        if (!isMatchingDestinationSlotFree(destinationContainer, localPlotX, localPlotZ)) {
            LOGGER.warn(
                "Cannot migrate Sable sublevel {} from {} to {}: matching destination plot slot {},{} is occupied",
                sourceSubLevel.getUniqueId(), sourceWorld.dimension().location(),
                destinationWorld.dimension().location(), localPlotX, localPlotZ
            );
            return;
        }

        List<EntityTransfer> entities = capturePlotEntities(sourceWorld, sourceSubLevel, portal);
        SubLevelData sourceData = SubLevelSerializer.toData(sourceSubLevel, List.of());
        SubLevelData destinationData = transformSerializedState(sourceData, portal);
        // Sable's disk format stores section array indices, relative to the owning
        // level's minimum build height. Keep absolute plot-space Y across levels.
        if (!rebasePlotSections(destinationData.fullTag(), sourceWorld.getMinSection(),
            destinationWorld.getMinSection(), destinationWorld.getSectionsCount())) {
            LOGGER.warn("Cannot migrate Sable sublevel {}: blocks exceed destination build height",
                sourceSubLevel.getUniqueId());
            return;
        }

        ServerSubLevel destinationSubLevel = SubLevelSerializer.fullyLoad(destinationWorld, destinationData);
        if (destinationSubLevel == null) {
            LOGGER.error(
                "Failed to load migrated Sable sublevel {} into {}",
                sourceSubLevel.getUniqueId(), destinationWorld.dimension().location()
            );
            return;
        }

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
            return;
        }

        sourceContainer.removeSubLevel(sourceSubLevel, SubLevelRemovalReason.REMOVED);
        LOGGER.debug(
            "Migrated Sable sublevel {} from {} to {} through stacked-dimension portal",
            destinationSubLevel.getUniqueId(), sourceWorld.dimension().location(),
            destinationWorld.dimension().location()
        );
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

    private static SubLevelData transformSerializedState(
        SubLevelData sourceData, VerticalConnectingPortal portal
    ) {
        CompoundTag tag = sourceData.fullTag().copy();
        Pose3d pose = SableNBTUtils.readPose3d(tag.getCompound("pose"));

        Vec3 destinationPosition = portal.transformPoint(JOMLConversion.toMojang(pose.position()));
        pose.position().set(destinationPosition.x, destinationPosition.y, destinationPosition.z);

        DQuaternion rotation = portal.getRotation();
        if (rotation != null) {
            pose.orientation().premul(new Quaterniond(rotation.x, rotation.y, rotation.z, rotation.w));
        }
        tag.put("pose", SableNBTUtils.writePose3d(pose));

        transformVelocity(tag, "linear_velocity", portal, true);
        transformVelocity(tag, "angular_velocity", portal, false);

        return new SubLevelData(
            sourceData.uuid(), sourceData.bounds(), pose, sourceData.dependencies(), tag
        );
    }

    private static void transformVelocity(
        CompoundTag tag, String key, VerticalConnectingPortal portal, boolean includeScale
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
        VerticalConnectingPortal portal
    ) {
        List<Entity> migrationEntities = new ArrayList<>();
        Set<UUID> migrationIds = new HashSet<>();
        Set<UUID> plotResidentIds = new HashSet<>();

        for (Entity entity : sourceWorld.getAllEntities()) {
            if (Sable.HELPER.getContaining(entity) == sourceSubLevel) {
                migrationEntities.add(entity);
                migrationIds.add(entity.getUUID());
                plotResidentIds.add(entity.getUUID());
            }
        }

        // Sable deliberately kicks ordinary riders of plot-resident vehicles into the
        // sublevel's logical world-space position. They are therefore not returned by
        // getContaining(), even though moving the vehicle without them would split the
        // riding graph across dimensions. Follow every passenger edge from the entities
        // retained in the plot so seats and their riders migrate as one transaction.
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
                // Detaching the riding graph can itself move passengers. Reposition every
                // entity during rollback, including entities whose transfer had not begun.
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
        // Player dimension changes have special vehicle handling in IP. Detach the graph first
        // so each migrated entity is transferred exactly once, then restore the graph.
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

    private record EntityTransfer(
        Entity entity,
        Vec3 storedPosition,
        Vec3 storedVelocity,
        Vec3 destinationPosition,
        Vec3 destinationVelocity,
        UUID vehicleId
    ) {}
}
