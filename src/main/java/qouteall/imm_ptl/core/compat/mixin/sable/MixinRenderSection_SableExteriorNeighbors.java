package qouteall.imm_ptl.core.compat.mixin.sable;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable.SableOwnedRenderSection;
import qouteall.imm_ptl.core.compat.sable.SableSectionRenderOwner;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class MixinRenderSection_SableExteriorNeighbors implements SableOwnedRenderSection {
    @Unique private volatile SableSectionRenderOwner ip_sableOwner;

    @Override public void ip_setSableRenderOwner(SableSectionRenderOwner owner) { ip_sableOwner=owner; }

    @Inject(method="hasAllNeighbors",at=@At("RETURN"),cancellable=true)
    private void ip_acceptFinalizedExteriorAir(CallbackInfoReturnable<Boolean> cir) {
        var owner=ip_sableOwner;
        if (!cir.getReturnValue() && owner!=null
            && owner.canUseExteriorAir((SectionRenderDispatcher.RenderSection)(Object)this)
            && ip_sableOwner==owner) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method="reset",at=@At("HEAD"))
    private void ip_forgetOwnerOnReset(CallbackInfo ci) { ip_sableOwner=null; }
}
