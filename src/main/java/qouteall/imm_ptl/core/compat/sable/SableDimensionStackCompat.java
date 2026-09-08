package qouteall.imm_ptl.core.compat.sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
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
 * serializer, moves plot-resident entities to the matching destination plot, and only
 * then removes the source copy.</p>
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

        List<EntityTransfer> entities = capturePlotEntities(sourceWorld, sourceSubLevel);
        SubLevelData sourceData = SubLevelSerializer.toData(sourceSubLevel, List.of());
        SubLevelData destinationData = transformSerializedState(sourceData, portal);

        ServerSubLevel destinationSubLevel = SubLevelSerializer.fullyLoad(destinationWorld, destinationData);
        if (destinationSubLevel == null) {
            LOGGER.error(
                "Failed to load migrated Sable sublevel {} into {}",
                sourceSubLevel.getUniqueId(), destinationWorld.dimension().location()
            );
            return;
        }

        try {
            transferPlotEntities(entities, destinationWorld);
        }
        catch (RuntimeException exception) {
            // Source data still exists at this point. Remove the failed destination copy and
            // leave the original sublevel intact rather than committing a partial migration.
            destinationContainer.removeSubLevel(destinationSubLevel, SubLevelRemovalReason.REMOVED);
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
        ServerLevel sourceWorld, ServerSubLevel sourceSubLevel
    ) {
        List<Entity> contained = new ArrayList<>();
        Set<UUID> containedIds = new HashSet<>();

        for (Entity entity : sourceWorld.getAllEntities()) {
            if (Sable.HELPER.getContaining(entity) == sourceSubLevel) {
                contained.add(entity);
                containedIds.add(entity.getUUID());
            }
        }

        List<EntityTransfer> transfers = new ArrayList<>(contained.size());
        for (Entity entity : contained) {
            Entity vehicle = entity.getVehicle();
            UUID vehicleId = vehicle != null && containedIds.contains(vehicle.getUUID())
                ? vehicle.getUUID()
                : null;
            transfers.add(new EntityTransfer(entity, entity.position(), entity.getDeltaMovement(), vehicleId));
        }
        return transfers;
    }

    private static void transferPlotEntities(
        List<EntityTransfer> transfers, ServerLevel destinationWorld
    ) {
        // Detach the graph first. Player dimension changes have special vehicle handling in IP;
        // leaving the graph attached here can teleport the same seat/vehicle twice.
        for (EntityTransfer transfer : transfers) {
            transfer.entity().stopRiding();
            transfer.entity().ejectPassengers();
        }

        Map<UUID, Entity> movedById = new HashMap<>();
        for (EntityTransfer transfer : transfers) {
            Entity moved = ServerTeleportationManager.teleportEntityGeneral(
                transfer.entity(), transfer.storedPosition(), destinationWorld
            );
            if (moved == null || moved.level() != destinationWorld) {
                throw new IllegalStateException("Entity failed cross-dimension transfer: " + transfer.entity());
            }
            moved.setDeltaMovement(transfer.storedVelocity());
            movedById.put(transfer.entity().getUUID(), moved);
        }

        for (EntityTransfer transfer : transfers) {
            if (transfer.vehicleId() == null) {
                continue;
            }
            Entity passenger = movedById.get(transfer.entity().getUUID());
            Entity vehicle = movedById.get(transfer.vehicleId());
            if (passenger == null || vehicle == null || !passenger.startRiding(vehicle, true)) {
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
        UUID vehicleId
    ) {}
}
