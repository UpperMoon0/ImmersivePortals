package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.ShaderClippingTransformation;

import java.util.Map;

@Pseudo
@Mixin(value = TransformPatcher.class, remap = false)
public class MixinIrisTransformPatcher {
    @Inject(method = "transform", at = @At("RETURN"), cancellable = true)
    private static void ip_afterTransform(
        String name, String vertex, String geometry, String tessControl, String tessEval, String fragment,
        Parameters parameters, CallbackInfoReturnable<Map<PatchShaderType, String>> cir
    ) {
        if (IPGlobal.enableClippingMechanism) {
            // Iris's transform cache excludes the program name. Never modify a cached map:
            // identical source may also be used by a shadow, sky or full-screen program.
            cir.setReturnValue(ShaderClippingTransformation.transformProgram(
                parameters.patch.name(), name, cir.getReturnValue()
            ));
        }
    }
}
