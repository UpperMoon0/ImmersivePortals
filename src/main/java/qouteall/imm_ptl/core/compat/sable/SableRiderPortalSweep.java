package qouteall.imm_ptl.core.compat.sable;

import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.PortalState;
import qouteall.imm_ptl.core.portal.shape.PortalShape;

/** Aperture checks for exact-plane contacts use the real shape, never its bounding rectangle. */
final class SableRiderPortalSweep {
    private SableRiderPortalSweep() {}

    static boolean planeContactInsideAperture(PortalShape shape, PortalState before, PortalState after,
                                             Vec3 localBefore, Vec3 localAfter) {
        if (!shape.isPlanar() || !finite(localBefore) || !finite(localAfter)) return false;
        if (localBefore.z > 0 && localAfter.z == 0) return contains(shape, after, localAfter);
        if (localBefore.z == 0 && localAfter.z <= 0) {
            return contains(shape, before, localBefore)
                && (localAfter.z < 0 || contains(shape, after, localAfter));
        }
        return false;
    }

    private static boolean contains(PortalShape shape, PortalState state, Vec3 point) {
        return shape.raytracePortalShapeByLocalPos(state.getThisSideState(),
            new Vec3(point.x, point.y, 1.0e-7), new Vec3(point.x, point.y, -1.0e-7), 0) != null;
    }

    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
