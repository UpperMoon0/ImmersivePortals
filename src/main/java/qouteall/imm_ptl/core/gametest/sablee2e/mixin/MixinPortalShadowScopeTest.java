package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalShadowTestControl;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/** Enter the production scope with an actual inherited clip bit; do not replace its behavior. */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinPortalShadowScopeTest {
    @WrapOperation(method = "renderShadows", at = @At(value = "INVOKE", target =
        "Lnet/irisshaders/iris/shadows/ShadowRenderer;renderShadows(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V"))
    private void ip_testInheritedShadowScope(ShadowRenderer renderer, LevelRendererAccessor levelRenderer,
        Camera camera, Operation<Void> original) {
        if (!PortalShadowTestControl.enabled() || !PortalRendering.isRendering()) {
            original.call(renderer, levelRenderer, camera);
            return;
        }
        FrontClipping.ClippingState previous = FrontClipping.captureClippingState();
        try {
            FrontClipping.setupInnerClipping(PortalRendering.getActiveClippingPlane(), new Matrix4f(), 0);
            if (!FrontClipping.isClippingEnabled || !GL11.glIsEnabled(GL11.GL_CLIP_PLANE0)) {
                throw new IllegalStateException("Shadow acceptance did not establish inherited portal clipping");
            }
            original.call(renderer, levelRenderer, camera);
            var access = (PortalShadowTargetsAccess) renderer;
            var targets = access.ip_shadowTargets();
            PortalShadowTestControl.recordRenderer(java.util.Map.of(
                "terrain", access.ip_shadowTerrainDebug(),
                "should_render_terrain", access.ip_shouldRenderShadowTerrain(),
                "render_distance_multiplier", access.ip_shadowRenderDistanceMultiplier(),
                "configured_shadow_distance", net.irisshaders.iris.gui.option.IrisVideoSettings.shadowDistance,
                "effective_shadow_distance", net.irisshaders.iris.gui.option.IrisVideoSettings.getOverriddenShadowDistance(
                    net.irisshaders.iris.gui.option.IrisVideoSettings.shadowDistance),
                "render_distance", ShadowRenderer.renderDistance));
            PortalShadowTestControl.capture(targets.getDepthTexture().getTextureId(), targets.getResolution(),
                FrontClipping.isClippingEnabled && GL11.glIsEnabled(GL11.GL_CLIP_PLANE0));
        } finally {
            FrontClipping.restoreClippingState(previous);
        }
    }
}
