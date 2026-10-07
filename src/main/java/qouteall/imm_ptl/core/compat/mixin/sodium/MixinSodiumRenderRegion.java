package qouteall.imm_ptl.core.compat.mixin.sodium;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderRegion;
import qouteall.q_misc_util.Helper;

import java.util.IdentityHashMap;
import java.util.Map;

@Mixin(value = RenderRegion.class, remap = false)
public class MixinSodiumRenderRegion implements IESodiumRenderRegion {
    @Shadow
    @Final
    private ChunkRenderList renderList;
    
    @Unique
    private @Nullable ObjectArrayList<ChunkRenderList> chunkRenderListsForPortalRendering = null;

    @Unique private final Map<TerrainRenderPass, ChunkRenderList> ip_batchRenderLists = new IdentityHashMap<>();
    @Unique private final Map<TerrainRenderPass, MultiDrawBatch> ip_batches = new IdentityHashMap<>();

    @Override
    public void ip_prepareBatchForRenderList(TerrainRenderPass pass, MultiDrawBatch batch, ChunkRenderList renderList) {
        ChunkRenderList previousList = ip_batchRenderLists.put(pass, renderList);
        MultiDrawBatch previousBatch = ip_batches.put(pass, batch);
        // Each portal layer tracks changes to its own list. Sodium's batch is instead
        // region-owned, so an unchanged inner list may find the outer view's commands.
        // Check at draw time: the outer translucent draw resumes without re-traversal.
        // Include batch identity because Iris swaps regular/shadow maps independently.
        if (previousList != renderList || previousBatch != batch) {
            batch.clear();
        }
    }
    
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
