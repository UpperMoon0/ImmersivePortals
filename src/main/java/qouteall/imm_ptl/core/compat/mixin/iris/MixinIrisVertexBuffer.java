package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.iris_compatibility.IEIrisClippingShader;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Mixin(VertexBuffer.class)
public class MixinIrisVertexBuffer {
    @WrapMethod(method = "_drawWithShader")
    private void ip_clipWorldDraw(
        Matrix4f modelView, Matrix4f projection, ShaderInstance shader, Operation<Void> original
    ) {
        // Translucent entity buffers can flush after the entity scope ends, and particles
        // have no vanilla clipping scope. Only a transformed world shader can enable this.
        boolean transformed = shader instanceof IEIrisClippingShader clippingShader
            && clippingShader.ip_hasClippingEquation() && !ShadowRenderer.ACTIVE;
        boolean addClipping = transformed && !FrontClipping.isClippingEnabled && PortalRendering.isRendering();
        FrontClipping.ClippingState previous = null;
        if (!transformed && FrontClipping.isClippingEnabled) {
            // An unmarked shader need not write gl_ClipDistance. Leaving the coarse
            // world's clip enable bit on would make its clipping undefined.
            previous = FrontClipping.suspendClipping();
        }
        else if (addClipping) {
            previous = FrontClipping.captureClippingState();
            FrontClipping.setupInnerClipping(PortalRendering.getActiveClippingPlane(), modelView, 0);
        }
        try {
            original.call(modelView, projection, shader);
        }
        finally {
            if (previous != null) FrontClipping.restoreClippingState(previous);
        }
    }
}
