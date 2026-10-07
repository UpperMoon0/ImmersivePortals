package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.FallbackShader;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IEIrisClippingShader;
import qouteall.imm_ptl.core.render.FrontClipping;

/** Iris bypasses ShaderInstance.apply, so its entity/particle uniforms need their own upload. */
@Mixin(value = {ExtendedShader.class, FallbackShader.class}, remap = false)
public class MixinIrisWorldShader implements IEIrisClippingShader {
    @Unique
    private int ip_clippingEquationLocation = -1;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ip_findClippingUniform(CallbackInfo ci) {
        ip_clippingEquationLocation = GL20C.glGetUniformLocation(
            ((ShaderInstance) (Object) this).getId(), "iportal_ClippingEquation"
        );
    }

    @Override
    public boolean ip_hasClippingEquation() {
        return ip_clippingEquationLocation >= 0;
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void ip_uploadClippingUniform(CallbackInfo ci) {
        if (ip_clippingEquationLocation < 0) return;
        // Upload on every draw, including recursive portals and the return to the outer world.
        // Iris's per-frame uniform cache cannot observe clipping-plane changes within a frame.
        double[] equation = FrontClipping.getActiveClipPlaneEquationAfterModelView();
        if (IPGlobal.enableClippingMechanism && FrontClipping.isClippingEnabled
            && !ShadowRenderer.ACTIVE && equation != null) {
            GL20C.glUniform4f(ip_clippingEquationLocation,
                (float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]);
        }
        else {
            GL20C.glUniform4f(ip_clippingEquationLocation, 0, 0, 0, 1);
        }
    }
}
