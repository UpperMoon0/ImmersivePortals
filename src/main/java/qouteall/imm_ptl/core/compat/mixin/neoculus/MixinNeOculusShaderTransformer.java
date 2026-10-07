package qouteall.imm_ptl.core.compat.mixin.neoculus;

import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.ShaderClippingTransformation;

import java.util.Map;

/** NeOculus terrain bypasses Iris's TransformPatcher and uses this independent compiler/cache. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.embeddium.impl.monocle.ShaderTransformer", remap = false)
public class MixinNeOculusShaderTransformer {
    @Inject(method = "transform", at = @At("RETURN"), cancellable = true)
    private static void ip_afterTransform(CallbackInfoReturnable<Map<PatchShaderType, String>> cir,
        @Local(argsOnly = true, ordinal = 0) String name) {
        if (IPGlobal.enableClippingMechanism) {
            // Like Iris, this cache omits the program name. Copy only the selected
            // world program and leave cached shadow/fullscreen sources untouched.
            cir.setReturnValue(ShaderClippingTransformation.transformProgram("EMBEDDIUM", name, cir.getReturnValue()));
        }
    }
}
