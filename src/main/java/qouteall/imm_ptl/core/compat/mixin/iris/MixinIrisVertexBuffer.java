package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.Iris;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.iris_compatibility.IEIrisClippingShader;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisClippingPolicy;
import qouteall.imm_ptl.core.ducks.IEShader;
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
        boolean irisClippingShader = shader instanceof IEIrisClippingShader irisShader
            && irisShader.ip_hasClippingEquation();
        boolean vanillaClippingShader = shader instanceof IEShader vanillaShader
            && vanillaShader.ip_getClippingEquationUniform() != null;
        IrisClippingPolicy.Action action = IrisClippingPolicy.forDraw(
            Iris.getCurrentPack().isPresent(), ShadowRenderer.ACTIVE, PortalRendering.isRendering(),
            FrontClipping.isClippingEnabled, irisClippingShader, vanillaClippingShader
        );
        FrontClipping.ClippingState previous = null;
        if (action == IrisClippingPolicy.Action.SUSPEND) {
            // An unpatched active-pack shader need not write gl_ClipDistance.
            // Preserve the separate vanilla/IP clipping-uniform paths.
            previous = FrontClipping.suspendClipping();
        }
        else if (action == IrisClippingPolicy.Action.ENABLE_WORLD_CLIPPING) {
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
