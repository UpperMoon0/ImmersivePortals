package qouteall.imm_ptl.core.compat.mixin.embeddium;

import net.minecraft.client.Camera;
import org.embeddedt.embeddium.impl.render.EmbeddiumWorldRenderer;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.embeddium_compatibility.EmbeddiumInterface;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;

@Mixin(value = EmbeddiumWorldRenderer.class, remap = false)
public class MixinEmbeddiumWorldRenderer {
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
