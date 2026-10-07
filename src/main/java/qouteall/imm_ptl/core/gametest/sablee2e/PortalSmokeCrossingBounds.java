package qouteall.imm_ptl.core.gametest.sablee2e;

/** Open observation aisle between the destination portal z=0 and wall face z=-3. */
public final class PortalSmokeCrossingBounds {
    private PortalSmokeCrossingBounds() {}

    public static boolean contains(double x, double eyeY, double z) {
        return Double.isFinite(x) && Double.isFinite(eyeY) && Double.isFinite(z)
            && Math.abs(x) <= 0.25 && Math.abs(eyeY - 82.0) <= 0.25 && z >= -2.0 && z <= 0.25;
    }
}
