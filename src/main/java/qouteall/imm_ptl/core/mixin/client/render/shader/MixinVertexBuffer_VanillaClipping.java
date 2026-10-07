package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.iris_compatibility.IEIrisClippingShader;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.VanillaClippingPolicy;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Damage overlays and provider-batched vanilla entities can flush after the coarse
 * entity scope ends. This includes Create's chunk render types for contraptions and
 * kinetic block entities. Iris/NeOculus keep batching even with their shaderpack off.
 * Vanilla section terrain uses VertexBuffer.draw directly and retains its own scope.
 */
@Mixin(VertexBuffer.class)
public class MixinVertexBuffer_VanillaClipping {
    @WrapMethod(method = "_drawWithShader")
    private void ip_clipVanillaWorldDraw(
        Matrix4f modelView, Matrix4f projection, ShaderInstance shader, Operation<Void> original
    ) {
        if (shader instanceof IEIrisClippingShader
            || !VanillaClippingPolicy.needsDrawScope(shader.getName()) || !IPGlobal.enableClippingMechanism
            || IrisInterface.invoker.isRenderingShadowMap()) {
            original.call(modelView, projection, shader);
            return;
        }
        Uniform uniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (uniform == null) {
            original.call(modelView, projection, shader);
            return;
        }
        FrontClipping.ClippingState previous = FrontClipping.captureClippingState();
        try {
            if (VanillaClippingPolicy.shouldStartPortalScope(PortalRendering.isRendering(), FrontClipping.isClippingEnabled)) {
                FrontClipping.setupInnerClipping(PortalRendering.getActiveClippingPlane(), modelView, 0);
            }
            // Set the actual shader argument immediately before its apply/upload;
            // RenderSystem.setShader ran before this late clipping scope existed.
            // Terrain-named shaders evaluate Position + ChunkOffset in the YAML;
            // preserve their offset and pass the same camera-relative equation.
            double[] equation = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
            if (FrontClipping.isClippingEnabled && equation != null) {
                uniform.set((float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]);
            }
            else {
                uniform.set(0f, 0f, 0f, 1f);
            }
            original.call(modelView, projection, shader);
        }
        finally {
            uniform.set(0f, 0f, 0f, 1f);
            FrontClipping.restoreClippingState(previous);
        }
    }
}
