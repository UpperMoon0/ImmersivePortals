package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalClippingTestControl;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public class MixinIrisProgramSourceTest {
    @Inject(method = "create", at = @At("HEAD"))
    private static void ip_observeSource(CallbackInfoReturnable<?> cir,
        @Local(argsOnly = true) String name, @Local(argsOnly = true) ProgramSource source) {
        PortalClippingTestControl.recordSource(name, source.getName());
    }

    @Inject(method = "createFallback", at = @At("HEAD"))
    private static void ip_observeFallback(CallbackInfoReturnable<?> cir, @Local(argsOnly = true) String name) {
        PortalClippingTestControl.recordSource(name, "<synthesized>");
    }
}
