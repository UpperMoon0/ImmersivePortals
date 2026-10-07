package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderRegion;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class MixinSodiumDefaultChunkRenderer {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;getCachedBatch(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;"))
    private MultiDrawBatch ip_bindBatchToCurrentView(RenderRegion region, TerrainRenderPass pass,
        Operation<MultiDrawBatch> original, @Local ChunkRenderList renderList) {
        MultiDrawBatch batch = original.call(region, pass);
        ((IESodiumRenderRegion) region).ip_prepareBatchForRenderList(pass, batch, renderList);
        return batch;
    }
}
