package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.ShaderClippingTransformation;

@Mixin(value = ShaderCreator.class, remap = false)
public class MixinIrisShaderCreator {
    // If the entire ProgramId fallback chain is absent, Iris synthesizes a vanilla
    // shader directly and never calls TransformPatcher. Its matrix names are unprefixed.
    @ModifyExpressionValue(
        method = "createFallback",
        at = @At(value = "INVOKE", target =
            "Lnet/irisshaders/iris/pipeline/fallback/ShaderSynthesizer;vsh(ZLnet/irisshaders/iris/gl/state/ShaderAttributeInputs;Lnet/irisshaders/iris/gl/state/FogMode;ZZ)Ljava/lang/String;")
    )
    private static String ip_clipSynthesizedWorldShader(String source, @Local(argsOnly = true) String name) {
        return IPGlobal.enableClippingMechanism && ShaderClippingTransformation.isWorldProgram("VANILLA", name)
            ? ShaderClippingTransformation.transform(source, ShaderClippingTransformation.Stage.VERTEX, "ProjMat")
            : source;
    }
}
