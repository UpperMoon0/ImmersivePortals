package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatibilityPortalRenderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisPortalRenderer;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalShaderDiagnostics;

@Mixin(value = {IrisPortalRenderer.class, IrisCompatibilityPortalRenderer.class}, remap = false)
public class MixinIrisInnerDepthTest {
    @Inject(method = "onBeforeHandRendering", at = @At("HEAD"))
    private void ip_captureInnerWorldDepth(Matrix4f modelView, CallbackInfo ci) {
        // This hook runs before these renderers draw nested portals or composite
        // the child world into the parent's aperture. Final main depth is too late.
        PortalShaderDiagnostics.captureInnerDepth();
    }
}
