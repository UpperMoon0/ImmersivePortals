package qouteall.imm_ptl.core.compat.mixin.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
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
     * Run migration after Sable has snapshotted lastPose and completed the full physics step.
     * Pin the exact descriptor and require the injection to match so a Sable API drift cannot
     * silently disable stacked-dimension migration.
     */
    @Inject(
        method = "tick(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V",
        at = @At("TAIL"),
        require = 1
    )
    private void ip_migrateAcrossDimensionStacks(SubLevelContainer container, CallbackInfo ci) {
        if (container instanceof ServerSubLevelContainer serverContainer) {
            SableDimensionStackCompat.afterPhysicsTick(level, serverContainer);
        }
    }
}
