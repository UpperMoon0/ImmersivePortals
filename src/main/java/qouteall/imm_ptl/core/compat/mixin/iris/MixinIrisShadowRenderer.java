package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.render.FrontClipping;

@Mixin(value = ShadowRenderer.class, remap = false)
public class MixinIrisShadowRenderer {
    @WrapMethod(method = "renderShadows")
    private void ip_suspendPortalClipping(
        LevelRendererAccessor levelRenderer, Camera camera, Operation<Void> original
    ) {
        // Shadow terrain uses direct chunk draws. Neutral uniforms alone cannot
        // protect untransformed shadow programs when GL_CLIP_DISTANCE0 is enabled.
        FrontClipping.ClippingState previous = FrontClipping.suspendClipping();
        try {
            original.call(levelRenderer, camera);
        }
        finally {
            FrontClipping.restoreClippingState(previous);
        }
    }
}
