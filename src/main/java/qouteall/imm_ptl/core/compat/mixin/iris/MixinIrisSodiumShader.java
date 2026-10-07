package qouteall.imm_ptl.core.compat.mixin.iris;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniformFloat4v;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.pipeline.programs.SodiumShader;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.FrontClipping;

import java.util.List;
import java.util.function.Supplier;

@Mixin(value = SodiumShader.class, remap = false)
public class MixinIrisSodiumShader {
    @Unique
    private GlUniformFloat4v uIPClippingEquation;
    
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void onInit(IrisRenderingPipeline pipeline, SodiumPrograms.Pass pass, ShaderBindingContext context, int handle, BlendModeOverride blendModeOverride, List bufferBlendOverrides, CustomUniforms customUniforms, Supplier flipState, float alphaTest, boolean containsTessellation, CallbackInfo ci) {
        
        this.uIPClippingEquation = context.bindUniformOptional("iportal_ClippingEquation", GlUniformFloat4v::new);
    }
    
    @Inject(
        method = "setupState",
        at = @At("RETURN"),
        remap = false
    )
    private void onSetup(CallbackInfo ci) {
        if (uIPClippingEquation != null) {
            double[] equation = FrontClipping.getActiveClipPlaneEquationAfterModelView();
            if (IPGlobal.enableClippingMechanism && FrontClipping.isClippingEnabled
                && !ShadowRenderer.ACTIVE && equation != null) {
                uIPClippingEquation.set(new float[]{
                    (float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]
                });
            }
            else {
                uIPClippingEquation.set(new float[]{0, 0, 0, 1});
            }
        }
    }

}
