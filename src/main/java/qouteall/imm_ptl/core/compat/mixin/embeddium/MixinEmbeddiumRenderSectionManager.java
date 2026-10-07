package qouteall.imm_ptl.core.compat.mixin.embeddium;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.embeddedt.embeddium.impl.render.chunk.ChunkUpdateType;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
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

import java.util.ArrayDeque;
import java.util.Map;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinEmbeddiumRenderSectionManager implements IEEmbeddiumRenderSectionManager {
    @Shadow @Final @Mutable private int renderDistance;
    @Shadow private SortedRenderLists renderLists;
    @Shadow private Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists;
    @Shadow private int lastUpdatedFrame;
    @Shadow private BlockPos lastCameraPosition;
    @Shadow private Vec3 cameraPosition;

    @Override
    public void ip_swapContext(EmbeddiumRenderingContext context) {
        var lists = renderLists;
        renderLists = context.renderLists;
        context.renderLists = lists;
        var rebuilds = rebuildLists;
        rebuildLists = context.rebuildLists;
        context.rebuildLists = rebuilds;
        int distance = renderDistance;
        renderDistance = context.renderDistance;
        context.renderDistance = distance;
        int frame = lastUpdatedFrame;
        lastUpdatedFrame = context.lastUpdatedFrame;
        context.lastUpdatedFrame = frame;
        var lastPosition = lastCameraPosition;
        lastCameraPosition = context.lastCameraPosition;
        context.lastCameraPosition = lastPosition;
        var position = cameraPosition;
        cameraPosition = context.cameraPosition;
        context.cameraPosition = position;
    }

    // A section's visibility frame is shared by recursive views. It cannot safely cull entities afterwards.
    @Inject(method = "isSectionVisible", at = @At("HEAD"), cancellable = true)
    private void ip_keepEntitiesAfterPortal(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
        if (RenderStates.portalsRenderedThisFrame != 0) cir.setReturnValue(true);
    }
}
