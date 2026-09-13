package qouteall.imm_ptl.core.compat.mixin.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat;

@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class MixinSubLevelPhysicsSystem_SableDimensionStackCompat {
    @Shadow @Final private ServerLevel level;

    /**
     * Sable calls updateAllPoses once after every native physics substep. Hooking that exact
     * point gives portal crossing the same temporal resolution as physics and avoids a tall or
     * fast body spending the remainder of a game tick attached to the wrong dimension.
     */
    @Inject(
        method = "updateAllPoses(Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;)V",
        at = @At("TAIL"),
        require = 1
    )
    private void ip_migrateAcrossPortalsAfterSubstep(
        ServerSubLevelContainer container, CallbackInfo ci
    ) {
        SableDimensionStackCompat.afterPhysicsSubstep(level, container);
    }
}
