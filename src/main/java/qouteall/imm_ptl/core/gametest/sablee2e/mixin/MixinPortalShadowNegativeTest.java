package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import net.irisshaders.iris.shadows.ShadowRenderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalShadowTestControl;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/** Runs in the wrapped method body, after the production suspension scope has entered. */
@Mixin(value = ShadowRenderer.class, remap = false)
public class MixinPortalShadowNegativeTest {
    @Inject(method = "renderShadows", at = @At("HEAD"))
    private void ip_reenableClipForNegative(CallbackInfo ci) {
        if (PortalShadowTestControl.enabled() && PortalShadowTestControl.poison() && PortalRendering.isRendering()) {
            GL11.glEnable(GL11.GL_CLIP_PLANE0);
        }
    }
}
