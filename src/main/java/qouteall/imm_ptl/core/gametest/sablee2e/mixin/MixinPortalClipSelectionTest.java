package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalClippingTestControl;
import qouteall.imm_ptl.core.render.ShaderClippingTransformation;

@Mixin(value = ShaderClippingTransformation.class, remap = false)
public class MixinPortalClipSelectionTest {
    @Inject(method = "isWorldProgram", at = @At("RETURN"), cancellable = true)
    private static void ip_testSelection(String patch, String name, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(PortalClippingTestControl.select(patch, name, cir.getReturnValue()));
    }
}
