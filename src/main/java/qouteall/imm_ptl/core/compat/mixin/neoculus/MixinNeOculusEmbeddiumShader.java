package qouteall.imm_ptl.core.compat.mixin.neoculus;

import com.llamalad7.mixinextras.sugar.Local;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.FrontClipping;

/** NeOculus 1.8.7 uses an Embeddium shader interface, not Iris's SodiumShader. */
@Pseudo // Separate optional artifact; IPCompatMixinPlugin requires the target when NeOculus is selected.
@Mixin(targets = "net.irisshaders.iris.compat.embeddium.impl.oculus.EmbeddiumShader", remap = false)
public class MixinNeOculusEmbeddiumShader {
    @Unique private int ip_clippingEquation = -1;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ip_bindClipPlane(CallbackInfo ci, @Local(argsOnly = true) int handle) {
        ip_clippingEquation = GL20C.glGetUniformLocation(handle, "iportal_ClippingEquation");
    }

    @Inject(method = "setupState", at = @At("RETURN"))
    private void ip_uploadClipPlane(CallbackInfo ci) {
        if (ip_clippingEquation < 0) return; // Shadow programs deliberately have no portal clipping.
        double[] equation = FrontClipping.getActiveClipPlaneEquationAfterModelView();
        if (IPGlobal.enableClippingMechanism && FrontClipping.isClippingEnabled && equation != null
            && !IrisInterface.invoker.isRenderingShadowMap()) {
            GL20C.glUniform4f(ip_clippingEquation, (float) equation[0], (float) equation[1],
                (float) equation[2], (float) equation[3]);
        } else {
            GL20C.glUniform4f(ip_clippingEquation, 0, 0, 0, 1);
        }
    }
}
