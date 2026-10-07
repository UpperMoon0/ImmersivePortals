package qouteall.imm_ptl.core.compat.mixin.embeddium;

import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.occlusion.OcclusionCuller;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.embeddium_compatibility.IEEmbeddiumViewport;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Mixin(value = OcclusionCuller.class, remap = false)
public abstract class MixinEmbeddiumOcclusionCuller {
    @Shadow private RenderSection getRenderSection(int x, int y, int z) { throw new AssertionError(); }
    @Unique private static boolean ip_allowInitialFrustumMiss;

    @ModifyVariable(method = "findVisible", at = @At("HEAD"), argsOnly = true)
    private boolean ip_preparePortalSearch(boolean original, OcclusionCuller.Visitor visitor,
                                           Viewport viewport, float distance, boolean useOcclusion, int frame) {
        ((IEEmbeddiumViewport) (Object) viewport).ip_setSearchOrigin(null);
        ip_allowInitialFrustumMiss = false;
        // Shadow casters outside the portal cone may still cast into its destination.
        if (!PortalRendering.isRendering() || IrisInterface.invoker.isRenderingShadowMap()) return original;
        var portal = PortalRendering.getRenderingPortal();
        var origin = portal.getPortalShape().getModifiedVisibleSectionIterationOrigin(portal, CHelper.getCurrentCameraPos());
        if (origin != null) {
            ((IEEmbeddiumViewport) (Object) viewport).ip_setSearchOrigin(origin);
            var section = getRenderSection(origin.x(), origin.y(), origin.z());
            ip_allowInitialFrustumMiss = section != null && !OcclusionCuller.isWithinFrustum(viewport, section);
            return false;
        }
        return original && PortalRendering.shouldEnableSodiumCaveCulling();
    }

    @Inject(method = "isWithinFrustum", at = @At("RETURN"), cancellable = true)
    private static void ip_traverseToPortalFrustum(Viewport viewport, RenderSection section, CallbackInfoReturnable<Boolean> cir) {
        if (ip_allowInitialFrustumMiss) {
            if (cir.getReturnValueZ()) ip_allowInitialFrustumMiss = false;
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "findVisible", at = @At("RETURN"))
    private void ip_finishPortalSearch(OcclusionCuller.Visitor visitor, Viewport viewport, float distance,
                                       boolean useOcclusion, int frame, CallbackInfo ci) {
        ((IEEmbeddiumViewport) (Object) viewport).ip_setSearchOrigin(null);
        ip_allowInitialFrustumMiss = false;
    }
}
