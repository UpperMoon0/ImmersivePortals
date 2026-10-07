package qouteall.imm_ptl.core.compat.embeddium_compatibility;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.embeddedt.embeddium.api.render.texture.SpriteUtil;
import org.embeddedt.embeddium.impl.render.EmbeddiumWorldRenderer;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkStatus;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import qouteall.imm_ptl.core.compat.mixin.embeddium.IEEmbeddiumWorldRenderer;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;

/** Embeddium implementation of the existing renderer-neutral invocation boundary. */
public final class EmbeddiumInterface extends SodiumInterface.Invoker {
    public static FrustumCuller frustumCuller;

    @Override
    public boolean isSodiumPresent() { return true; }

    @Override
    public Object createNewContext(int renderDistance) { return new EmbeddiumRenderingContext(renderDistance); }

    @Override
    public void switchContextWithCurrentWorldRenderer(Object value) {
        EmbeddiumRenderingContext context = (EmbeddiumRenderingContext) value;
        EmbeddiumWorldRenderer renderer = EmbeddiumWorldRenderer.instance();
        IEEmbeddiumWorldRenderer access = (IEEmbeddiumWorldRenderer) renderer;
        ((IEEmbeddiumRenderSectionManager) access.ip_getRenderSectionManager()).ip_swapContext(context);
        var viewport = access.ip_getViewport();
        access.ip_setViewport(context.viewport);
        context.viewport = viewport;
        var culler = frustumCuller;
        frustumCuller = context.frustumCuller;
        context.frustumCuller = culler;
        renderer.scheduleTerrainUpdate();
    }

    @Override
    public void markSpriteActive(TextureAtlasSprite sprite) { SpriteUtil.markSpriteActive(sprite); }

    @Override
    public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
        ChunkTrackerHolder.get(world).onChunkStatusAdded(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
    }

    @Override
    public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
        ChunkTrackerHolder.get(world).onChunkStatusRemoved(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
    }
}
