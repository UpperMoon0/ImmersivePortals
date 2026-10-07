package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ShadowRenderer.class, remap = false)
public interface PortalShadowTargetsAccess {
    @Accessor("targets") ShadowRenderTargets ip_shadowTargets();
}
