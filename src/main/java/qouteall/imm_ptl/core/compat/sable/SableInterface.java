package qouteall.imm_ptl.core.compat.sable;

import dev.ryanhcode.sable.Sable;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps Sable references behind an optional compatibility boundary.
 *
 * <p>Immersive Portals owns entity pairing while it is installed. Sable stores
 * entities inside remote plot chunks, so IP must use the entity's projected
 * logical position when choosing the chunk watch records that own that pairing.</p>
 */
public class SableInterface {
    public static class Invoker {
        public Vec3 getEntityTrackingPosition(Level level, Vec3 storedPosition) {
            return storedPosition;
        }
    }

    public static Invoker invoker = new Invoker();

    public static class OnSablePresent extends Invoker {
        @Override
        public Vec3 getEntityTrackingPosition(Level level, Vec3 storedPosition) {
            return Sable.HELPER.projectOutOfSubLevel(level, storedPosition);
        }
    }
}
