package qouteall.imm_ptl.core.compat.mixin.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat;

import java.util.UUID;

/**
 * Keeps hidden Sable plot coordinates globally unique across server dimensions. Runtime portal
 * transfer can then reconstruct a sublevel in the same plot slot without rewriting arbitrary
 * block-entity/attachment NBT that may contain absolute hidden-plot coordinates.
 */
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class MixinSubLevelContainer_SableGlobalPlotAllocator {
    @Inject(method = "allocateNewSubLevel", at = @At("HEAD"), cancellable = true)
    private void ip_allocateGloballyUniquePlot(
        Pose3d pose, CallbackInfoReturnable<SubLevel> cir
    ) {
        SubLevelContainer self = (SubLevelContainer) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) return;

        Vector2i slot = SableDimensionStackCompat.findGloballyFreePlot(serverLevel, self);
        if (slot == null) {
            throw new IllegalStateException(
                "No Sable plot slot is free across all server dimensions"
            );
        }

        cir.setReturnValue(self.allocateSubLevel(UUID.randomUUID(), slot.x, slot.y, pose));
    }
}
