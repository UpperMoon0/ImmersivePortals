package qouteall.imm_ptl.core.compat.mixin.sodium;

import it.unimi.dsi.fastutil.longs.LongCollection;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    @Redirect(method = "initRenderer", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/map/ChunkTracker;getReadyChunks()Lit/unimi/dsi/fastutil/longs/LongCollection;"))
    private static LongCollection ip_rebaseChunkEventsForReload(ChunkTracker tracker) {
        // initRenderer rebuilds sections from the current ready set. Pending events
        // still describe the destroyed manager: a queued unload can otherwise cancel
        // a future load, leaving a newly ready remote chunk without any sections.
        // Consume that old baseline before replaying the snapshot. Subsequent status
        // changes then enqueue events against the replacement manager.
        tracker.forEachEvent((x, z) -> {}, (x, z) -> {});
        return tracker.getReadyChunks();
    }

    @Inject(
        method = "setupTerrain",
        at = @At("HEAD")
    )
    private void onUpdateChunks(
        Camera camera, Viewport viewport, boolean spectator, boolean updateChunksImmediately, CallbackInfo ci
    ) {
        SodiumInterface.frustumCuller = new FrustumCuller();
        Vec3 cameraPos = camera.getPosition();
        SodiumInterface.frustumCuller.update(cameraPos.x, cameraPos.y, cameraPos.z);
    }
}
