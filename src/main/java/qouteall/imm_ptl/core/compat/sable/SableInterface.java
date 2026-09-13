package qouteall.imm_ptl.core.compat.sable;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

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
    }

    public static Invoker invoker = new Invoker();

    public static class OnSablePresent extends Invoker {
        @Override
        public Vec3 getEntityTrackingPosition(Level level, Vec3 storedPosition) {
            return Sable.HELPER.projectOutOfSubLevel(level, storedPosition);
        }

        @Override
        public void beforePlayerPortalTeleport(ServerPlayer player, Portal portal) {
            SableDimensionStackCompat.beforePlayerPortalTeleport(player, portal);
        }
    }
}
