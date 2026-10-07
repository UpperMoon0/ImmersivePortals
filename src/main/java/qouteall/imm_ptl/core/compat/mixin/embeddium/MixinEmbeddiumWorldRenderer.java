package qouteall.imm_ptl.core.compat.mixin.embeddium;

import it.unimi.dsi.fastutil.longs.LongCollection;
import org.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import net.minecraft.client.Camera;
import org.embeddedt.embeddium.impl.render.EmbeddiumWorldRenderer;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.embeddium_compatibility.EmbeddiumInterface;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;

@Mixin(value = EmbeddiumWorldRenderer.class, remap = false)
public class MixinEmbeddiumWorldRenderer {
    @Redirect(method = "initRenderer", at = @At(value = "INVOKE",
        target = "Lorg/embeddedt/embeddium/impl/render/chunk/map/ChunkTracker;getReadyChunks()Lit/unimi/dsi/fastutil/longs/LongCollection;"))
    private static LongCollection ip_rebaseChunkEventsForReload(ChunkTracker tracker) {
        // initRenderer rebuilds sections from the current ready set. Pending events
        // still describe the destroyed manager: a queued unload can otherwise cancel
        // a future load, leaving a newly ready remote chunk without any sections.
        // Consume that old baseline before replaying the snapshot. Subsequent status
        // changes then enqueue events against the replacement manager.
        tracker.forEachEvent((x, z) -> {}, (x, z) -> {});
        return tracker.getReadyChunks();
    }

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void ip_preparePortalFrustum(Camera camera, Viewport viewport, int frame,
                                        boolean spectator, boolean updateImmediately, CallbackInfo ci) {
        if (IrisInterface.invoker.isRenderingShadowMap()) {
            EmbeddiumInterface.frustumCuller = null;
            return;
        }
        var culler = new FrustumCuller();
        var position = camera.getPosition();
        culler.update(position.x, position.y, position.z);
        EmbeddiumInterface.frustumCuller = culler;
    }
}
