package qouteall.imm_ptl.core.compat.mixin.embeddium;

import org.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat4v;
import org.embeddedt.embeddium.impl.gl.shader.GlProgram;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import org.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderOptions;
import org.embeddedt.embeddium.impl.render.chunk.shader.ShaderBindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.opengl.GL20C;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.FrontClipping;

@Mixin(value = ChunkShaderInterface.class, remap = false)
public class MixinEmbeddiumChunkShaderInterface {
    @Unique private GlUniformFloat4v ip_clippingEquation;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ip_bindClipPlane(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci) {
        // NeOculus calls this superclass with a null pass and overrides setupState entirely.
        // Its independently transformed program is handled by MixinNeOculusEmbeddiumShader.
        if (options.pass() != null) {
            // Embeddium has no bindUniformOptional. Query the linked program so disabling
            // clipping before a resource reload does not make uniform binding throw.
            int location = GL20C.glGetUniformLocation(((GlProgram<?>) context).handle(), "iportal_ClippingEquation");
            if (location >= 0) ip_clippingEquation = new GlUniformFloat4v(location);
        }
    }

    @Inject(method = "setupState", at = @At("RETURN"))
    private void ip_uploadClipPlane(CallbackInfo ci) {
        if (ip_clippingEquation == null) return;
        double[] equation = FrontClipping.getActiveClipPlaneEquationAfterModelView();
        ip_clippingEquation.set(IPGlobal.enableClippingMechanism && FrontClipping.isClippingEnabled && equation != null
            ? new float[]{(float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]}
            : new float[]{0, 0, 0, 1});
    }
}
