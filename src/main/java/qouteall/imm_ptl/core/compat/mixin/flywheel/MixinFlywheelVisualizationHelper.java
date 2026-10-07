package qouteall.imm_ptl.core.compat.mixin.flywheel;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keep vanilla entries after Flywheel queues its visual. Vanilla, Embeddium and
 * Sodium all use this return value to remove visualized BEs from render lists.
 * A chunk may be compiled outside a portal then reused inside one, so this must
 * be unconditional. The dispatcher suppresses duplicates in the main view.
 */
@Mixin(targets = "dev.engine_room.flywheel.lib.visualization.VisualizationHelper", remap = false)
public abstract class MixinFlywheelVisualizationHelper {
    @Inject(method = "tryAddBlockEntity", at = @At("RETURN"), cancellable = true)
    private static void ip_retainVanillaRenderEntry(BlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
