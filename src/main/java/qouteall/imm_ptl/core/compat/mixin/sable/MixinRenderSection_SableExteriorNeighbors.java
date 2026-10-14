package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
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
    // Allocation provenance, independent of the revocable mesh-readiness owner.
    // Sable's dedicated sections never enter a terrain ViewArea and are not reused there.
    @Unique private volatile boolean ip_sableManagedSection;

    @Override public void ip_setSableRenderOwner(SableSectionRenderOwner owner) {
        if (owner!=null) ip_sableManagedSection=true;
        ip_sableOwner=owner;
    }

    /**
     * Sable renders its own section list, outside vanilla terrain occlusion. Its shared
     * dispatcher can retain a disposed terrain renderer after a vanilla dimension change.
     * Keep setCompiled's assignment and upload lifecycle; omit only this irrelevant event.
     * The provenance must survive reset: Sable close releases buffers before setCompiled(EMPTY).
     */
    @WrapOperation(
        method="setCompiled",
        at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;addRecentlyCompiledSection(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;)V"),
        require=1
    )
    private void ip_notifyOnlyTerrainSections(
        LevelRenderer renderer, SectionRenderDispatcher.RenderSection section, Operation<Void> original
    ) {
        if (!ip_sableManagedSection) original.call(renderer,section);
    }

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
