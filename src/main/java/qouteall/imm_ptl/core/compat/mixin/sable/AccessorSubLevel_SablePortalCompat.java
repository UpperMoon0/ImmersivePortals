package qouteall.imm_ptl.core.compat.mixin.sable;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accesses Sable's mutable previous-pose snapshot for cross-dimension interpolation continuity. */
@Mixin(value = SubLevel.class, remap = false)
public interface AccessorSubLevel_SablePortalCompat {
    @Accessor("lastPose")
    Pose3d ip_getLastPose();
}
