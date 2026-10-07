package qouteall.imm_ptl.core.compat.mixin.embeddium;

import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.embeddium_compatibility.EmbeddiumRenderingContext;
import qouteall.imm_ptl.core.compat.embeddium_compatibility.IEEmbeddiumRenderSectionManager;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinEmbeddiumRenderSectionManager implements IEEmbeddiumRenderSectionManager {
    @Shadow @Final @Mutable private int renderDistance;
    @Shadow private SortedRenderLists renderLists;

    @Override
    public void ip_swapContext(EmbeddiumRenderingContext context) {
        var lists = renderLists;
        renderLists = context.renderLists;
        context.renderLists = lists;
        // Rebuild queues are dimension-owned work, not view state. Embeddium consumes
        // last pass's queues BEFORE finding visible sections for this pass. Swapping
        // them into a temporary portal context would discard remote-only discoveries
        // before any later updateChunks call can submit their initial mesh builds.
        // lastUpdatedFrame, lastCameraPosition and cameraPosition likewise remain
        // associated with those queues for build timestamps and translucency sorting.
        int distance = renderDistance;
        renderDistance = context.renderDistance;
        context.renderDistance = distance;
    }

    // A section's visibility frame is shared by recursive views. It cannot safely cull entities afterwards.
    @Inject(method = "isSectionVisible", at = @At("HEAD"), cancellable = true)
    private void ip_keepEntitiesAfterPortal(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (RenderStates.portalsRenderedThisFrame != 0) cir.setReturnValue(true);
    }
}
