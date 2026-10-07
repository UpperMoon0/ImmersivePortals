package qouteall.imm_ptl.core.compat.mixin.flywheel;

import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.IPFlywheelCompat;

/** Both Flywheel and Create consult this before drawing or skipping vanilla geometry. */
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class MixinFlywheelVisualizationManager {
    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true)
    private static void ip_useVanillaInAlternateViews(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
        if (IPFlywheelCompat.useVanillaRenderer()) {
            IPFlywheelCompat.recordPortalFallback();
            cir.setReturnValue(false);
        }
    }
}
