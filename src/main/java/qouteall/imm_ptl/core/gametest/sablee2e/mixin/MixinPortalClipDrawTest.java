package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IEIrisClippingShader;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalClippingTestControl;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalShaderDiagnostics;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Mixin(VertexBuffer.class)
public class MixinPortalClipDrawTest {
    @Inject(method = "_drawWithShader", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V", shift = At.Shift.AFTER))
    private void ip_observeAppliedDraw(Matrix4f modelView, Matrix4f projection, ShaderInstance shader, CallbackInfo ci) {
        PortalShaderDiagnostics.captureBufferedDraw(shader, modelView, projection);
    }

    @Inject(method = "_drawWithShader", at = @At("RETURN"))
    private void ip_observeCompletedDraw(Matrix4f modelView, Matrix4f projection, ShaderInstance shader, CallbackInfo ci) {
        if (shader instanceof IEIrisClippingShader irisShader) {
            PortalClippingTestControl.recordDraw(shader.getName(), irisShader.ip_hasClippingEquation(), PortalRendering.isRendering());
        }
    }
}
