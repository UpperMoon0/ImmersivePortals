package qouteall.imm_ptl.core.compat.mixin.embeddium;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import org.embeddedt.embeddium.impl.render.chunk.lists.SortedRenderLists;
import org.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.Helper;

@Mixin(value = RenderRegion.class, remap = false)
public class MixinEmbeddiumRenderRegion {
    @Shadow
    @Final
    private ChunkRenderList renderList;
    
    @Unique
    private @Nullable ObjectArrayList<ChunkRenderList> chunkRenderListsForPortalRendering = null;
    
    /**
     * @author qouteall
     * @reason With ImmPtl, the world rendering process is as follows:
     * 1. render solid things
     * 2. render portal recursively (will increase frame counter)
     * 3. render transparent things
     * When rendering the world in portal (to-same-world portal),
     * the frame counter increases, then in
     * {@link SortedRenderLists.Builder#add(RenderSection)} it will reset the ChunkRenderList,
     * which makes upcoming transparent block rendering in outer world to break.
     * So use separate ChunkRenderList for each portal rendering layer.
     */
    @Overwrite
    public ChunkRenderList getRenderList() {
        if (!PortalRendering.isRendering()) {
            return renderList;
        }
        
        RenderRegion this_ = (RenderRegion) (Object) this;
        
        if (chunkRenderListsForPortalRendering == null) {
            chunkRenderListsForPortalRendering = new ObjectArrayList<>();
        }
        
        int layer = PortalRendering.getPortalLayer();
        int index = layer - 1;
        ChunkRenderList result = Helper.arrayListComputeIfAbsent(
            chunkRenderListsForPortalRendering,
            index,
            () -> new ChunkRenderList(this_)
        );
        
        return result;
    }
}
