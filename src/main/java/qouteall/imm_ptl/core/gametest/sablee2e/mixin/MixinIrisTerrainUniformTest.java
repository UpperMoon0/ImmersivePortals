package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalShaderDiagnostics;

@Pseudo
@Mixin(targets = {
    "net.irisshaders.iris.pipeline.programs.SodiumShader",
    "net.irisshaders.iris.compat.embeddium.impl.oculus.EmbeddiumShader"
}, remap = false)
public class MixinIrisTerrainUniformTest {
    @Inject(method = "setModelViewMatrix", at = @At("RETURN"))
    private void ip_captureTerrainUniforms(Matrix4fc matrix, CallbackInfo ci) {
        PortalShaderDiagnostics.captureTerrain(((Object) this).getClass().getSimpleName(), matrix);
    }
}
