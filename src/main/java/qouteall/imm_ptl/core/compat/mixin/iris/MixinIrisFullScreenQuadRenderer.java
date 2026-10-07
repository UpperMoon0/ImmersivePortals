package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.render.FrontClipping;

@Mixin(value = FullScreenQuadRenderer.class, remap = false)
public class MixinIrisFullScreenQuadRenderer {
    @WrapMethod(method = "renderQuad")
    private void ip_suspendPortalClipping(Operation<Void> original) {
        // Iris's composite/deferred/final/depth/color-space quads call draw()
        // directly, bypassing VertexBuffer._drawWithShader and its shader marker.
        FrontClipping.ClippingState previous = FrontClipping.suspendClipping();
        try {
            original.call();
        }
        finally {
            FrontClipping.restoreClippingState(previous);
        }
    }
}
