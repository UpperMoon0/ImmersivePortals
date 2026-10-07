package qouteall.imm_ptl.core.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import net.neoforged.fml.ModList;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.Helper;

/** Flywheel 1.x: retain the selected backend outside alternate/clipped views. */
public final class IPFlywheelCompat {
    public static boolean isFlywheelPresent;

    private static long portalFallbackQueries;
    private static long nestedContextsRestored;

    private IPFlywheelCompat() {}

    public static void recordPortalFallback() { portalFallbackQueries++; }
    public static long portalFallbackQueries() { return portalFallbackQueries; }
    public static void recordNestedContextRestored() { nestedContextsRestored++; }
    public static long nestedContextsRestored() { return nestedContextsRestored; }

    public static void init() {
        isFlywheelPresent = ModList.get().isLoaded("flywheel");
        if (isFlywheelPresent) {
            Helper.log("Flywheel portal compatibility: scoped vanilla rendering in alternate/clipped views");
        }
    }

    /**
     * Flywheel's shaders do not write IP's clip distance, and its per-level engine
     * is not reentrant. Query render scope rather than toggling the backend or
     * resetting managers: leaving a nested view immediately restores normal
     * visualization, including same-dimension portals. Chunk workers must not
     * observe a transient render-thread scope when registering visuals.
     */
    public static boolean useVanillaRenderer() {
        return RenderSystem.isOnRenderThread() && needsVanillaRenderer(
            WorldRenderInfo.isRendering(), PortalRendering.isRendering(), FrontClipping.isClippingEnabled
        );
    }

    static boolean needsVanillaRenderer(boolean alternateView, boolean portalView, boolean clippedView) {
        return alternateView || portalView || clippedView;
    }
}
