package qouteall.imm_ptl.core.compat.sable;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;

/**
 * Keeps Sable references behind an optional compatibility boundary.
 *
 * <p>Immersive Portals owns entity pairing while it is installed. Sable stores entities inside
 * remote plot chunks, so IP must use the projected logical position for tracking. The same
 * boundary also lets player teleportation hand a ridden Sable body to its destination before
 * the player receives the dimension switch, without loading Sable classes when the mod is
 * absent.</p>
 */
public class SableInterface {
    public static class Invoker {
        /**
         * Null keeps ordinary IP chunk tracking. An empty list still owns the plot's
         * recipient selection: hidden plot coordinates must never select world watchers.
         */
        public @Nullable List<ServerPlayer> getPlotChunkBroadcastPlayers(ServerLevel level, ChunkPos chunkPos) {
            return null;
        }

        public Vec3 getEntityTrackingPosition(Level level, Vec3 storedPosition) {
            return storedPosition;
        }

        public ChunkPos getEntityTrackingChunk(Level level, Vec3 storedPosition) {
            Vec3 trackingPosition = getEntityTrackingPosition(level, storedPosition);
            return new ChunkPos(
                SectionPos.blockToSectionCoord(trackingPosition.x),
                SectionPos.blockToSectionCoord(trackingPosition.z)
            );
        }

        public void beforePlayerPortalTeleport(ServerPlayer player, Portal portal) {
        }

        /**
         * Cross-dimension riders retained by Sable must let the server move the body first.
         * The default implementation keeps normal Immersive Portals prediction unchanged.
         */
        public boolean shouldUseServerFirstRiderTeleport(Entity vehicle, Portal portal) {
            return false;
        }

        public boolean shouldUseServerFirstStandingTeleport(Entity player, Portal portal) {
            return false;
        }

        public boolean isRetainedVehicle(Entity vehicle) {
            return false;
        }

        /** Advance Sable state for an Immersive Portals remote client world. */
        public void tickClientRemoteWorld(Level level) {
        }
    }

    public static Invoker invoker = new Invoker();

    public static class OnSablePresent extends Invoker {
        public OnSablePresent() {
            SableDimensionStackCompat.initAnimatedRiderWitnesses();
        }

        @Override
        public @Nullable List<ServerPlayer> getPlotChunkBroadcastPlayers(ServerLevel level, ChunkPos chunkPos) {
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container == null || !container.inBounds(chunkPos)) return null;

            // ChunkHolder's broadcastChanges is inherited by Sable's PlotChunkHolder.
            // IP replaces its PlayerProvider call, bypassing Sable's ChunkMap hook, so
            // restore the plot's actual tracking set here. Sable resolves these UUIDs
            // through the server PlayerList, including observers in other dimensions.
            return container.getPlayersTracking(chunkPos);
        }

        @Override
        public boolean isRetainedVehicle(Entity vehicle) {
            return vehicle != null && Sable.HELPER.getContaining(vehicle) != null
                && !dev.ryanhcode.sable.api.entity.EntitySubLevelUtil.shouldKick(vehicle);
        }

        @Override
        public Vec3 getEntityTrackingPosition(Level level, Vec3 storedPosition) {
            return Sable.HELPER.projectOutOfSubLevel(level, storedPosition);
        }

        @Override
        public void tickClientRemoteWorld(Level level) {
            if (!level.isClientSide) return;
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container != null) {
                container.tick();
            }
        }

        @Override
        public void beforePlayerPortalTeleport(ServerPlayer player, Portal portal) {
            SableDimensionStackCompat.beforePlayerPortalTeleport(player, portal);
        }

        @Override
        public boolean shouldUseServerFirstStandingTeleport(Entity player, Portal portal) {
            return !portal.hasScaling() && !player.level().dimension().equals(portal.getDestDim())
                && SableStandingPassenger.getSupport(player) != null;
        }

        @Override
        public boolean shouldUseServerFirstRiderTeleport(Entity vehicle, Portal portal) {
            return vehicle != null
                && vehicle.level().dimension() != portal.getDestDim()
                && Sable.HELPER.getContaining(vehicle) != null;
        }
    }
}
