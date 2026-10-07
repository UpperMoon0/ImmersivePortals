package qouteall.imm_ptl.core.compat.iris_compatibility;

/** Draw-scope policy without optional renderer or GL dependencies. */
public final class IrisClippingPolicy {
    public enum Action { PRESERVE, ENABLE_WORLD_CLIPPING, SUSPEND }

    private IrisClippingPolicy() {}

    public static Action forDraw(
        boolean shadersActive, boolean shadowPass, boolean portalRendering,
        boolean clippingEnabled, boolean irisClippingShader, boolean vanillaClippingShader
    ) {
        // Iris can remain installed with its shaders disabled. In that case the
        // vanilla/IP YAML transformations and their clipping scopes own the draw.
        if (!shadersActive) return Action.PRESERVE;
        if (shadowPass) return clippingEnabled ? Action.SUSPEND : Action.PRESERVE;
        if (irisClippingShader) {
            return portalRendering && !clippingEnabled ? Action.ENABLE_WORLD_CLIPPING : Action.PRESERVE;
        }
        // portal_area and vanilla programs implementing IEShader already write
        // gl_ClipDistance with the before-model-view plane. Preserve their scope.
        if (vanillaClippingShader) return Action.PRESERVE;
        return clippingEnabled ? Action.SUSPEND : Action.PRESERVE;
    }
}
