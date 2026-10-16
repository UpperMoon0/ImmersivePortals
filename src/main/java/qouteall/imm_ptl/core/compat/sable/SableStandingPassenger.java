package qouteall.imm_ptl.core.compat.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/** Collision-owned support, never a proximity or client-reported ship selection. */
public final class SableStandingPassenger {
    private SableStandingPassenger() {}

    public static @Nullable SubLevel getSupport(Entity entity) {
        if (entity.getVehicle() != null || entity.isRemoved() || !entity.isAlive()) return null;
        SubLevel body = Sable.HELPER.getTrackingSubLevel(entity);
        return body != null && !body.isRemoved() && body.getLevel() == entity.level() ? body : null;
    }
}
