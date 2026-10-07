package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.gl.GLDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisDebugLabelPolicy;

@Mixin(value = GLDebug.class, remap = false)
public class MixinIrisGLDebug {
    @Inject(method = "nameObject", at = @At("HEAD"), cancellable = true)
    private static void ip_skipAbsentDepthAttachment(int identifier, int object, String label, CallbackInfo ci) {
        // Iris MixinRenderTarget labels depthBufferId even when useDepth=false.
        // Its -1 sentinel is not an OpenGL object. Preserve every other debug call,
        // including invalid non-depth handles: this is not GL-error suppression.
        if (!IrisDebugLabelPolicy.shouldLabel(identifier, object, label)) ci.cancel();
    }
}
