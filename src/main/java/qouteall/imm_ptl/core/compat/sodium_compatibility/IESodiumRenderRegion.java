package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

public interface IESodiumRenderRegion {
    void ip_prepareBatchForRenderList(TerrainRenderPass pass, MultiDrawBatch batch, ChunkRenderList renderList);
}
