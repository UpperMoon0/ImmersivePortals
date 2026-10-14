package qouteall.imm_ptl.core.compat.sable;

/** Authoritative rigid-body point motion; deliberately independent of client/handoff state. */
final class SableRiderPortalApproach {
    private SableRiderPortalApproach() {}

    static boolean movesIntoPortal(
        double linearX, double linearY, double linearZ,
        double angularX, double angularY, double angularZ,
        double offsetX, double offsetY, double offsetZ,
        double normalX, double normalY, double normalZ
    ) {
        return movesIntoPortal(
            linearX, linearY, linearZ, angularX, angularY, angularZ,
            offsetX, offsetY, offsetZ, normalX, normalY, normalZ, 0, 0, 0
        );
    }

    static boolean movesIntoPortal(
        double linearX, double linearY, double linearZ,
        double angularX, double angularY, double angularZ,
        double offsetX, double offsetY, double offsetZ,
        double normalX, double normalY, double normalZ,
        double portalPointXPerTick, double portalPointYPerTick, double portalPointZPerTick
    ) {
        // Global velocity at the authoritative mounted eye: v(COM) + angularVelocity x offset(COM).
        // IP's portal point displacement is blocks/tick; native Sable velocity is m/s.
        double pointX = linearX + angularY * offsetZ - angularZ * offsetY - 20 * portalPointXPerTick;
        double pointY = linearY + angularZ * offsetX - angularX * offsetZ - 20 * portalPointYPerTick;
        double pointZ = linearZ + angularX * offsetY - angularY * offsetX - 20 * portalPointZPerTick;
        double normalSpeed = pointX * normalX + pointY * normalY + pointZ * normalZ;
        // No speed threshold: slow genuine approaches must work, but stationary, tangential
        // and invalid samples cannot authorize a dimension change from a client prediction.
        return Double.isFinite(normalSpeed) && normalSpeed < 0;
    }
}
