package qouteall.imm_ptl.core.compat.valkyrienskies;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import java.util.function.BiPredicate;

/**
 * Sweeps the physical boundary of a ship, not just its center of mass.
 *
 * <p>Every boundary sample crossing the portal's plane must pass the actual
 * portal shape. This deliberately fails closed when a sample reaches the
 * plane outside the aperture. It is a candidate test, not authorization to
 * mutate physics state: the transfer coordinator must additionally validate
 * loaded destination, ownership, passengers, and native VS transfer support.
 */
public final class ShipPortalSweep {
    private static final double EPSILON = 1.0e-7;

    public enum Status { NO_CROSSING, APERTURE_MISS, CANDIDATE }

    public record Result(Status status, int crossedSamples, int rejectedSamples) {
        public boolean canBeginTransfer() {
            return status == Status.CANDIDATE && crossedSamples > 0 && rejectedSamples == 0;
        }
    }

    private ShipPortalSweep() {}

    public static Result evaluate(
        Matrix4dc previousShipToWorld,
        Matrix4dc currentShipToWorld,
        AABB shipLocalBounds,
        Vec3 portalOrigin,
        Vec3 portalNormal,
        BiPredicate<Vec3, Vec3> crossesActualAperture
    ) {
        if (!previousShipToWorld.isFinite() || !currentShipToWorld.isFinite()
            || !finite(portalOrigin) || !finite(portalNormal)
            || portalNormal.lengthSqr() < EPSILON
            || !valid(shipLocalBounds)) {
            return new Result(Status.APERTURE_MISS, 0, 1);
        }

        int crossings = 0;
        int misses = 0;
        Vec3 n = portalNormal.normalize();

        // Eight corners, six face centers and one central witness. Face samples
        // detect gaps that a simple corner-only swept AABB would miss.
        for (int i = 0; i < 15; i++) {
            double x = (i & 1) == 0 ? shipLocalBounds.minX : shipLocalBounds.maxX;
            double y = (i & 2) == 0 ? shipLocalBounds.minY : shipLocalBounds.maxY;
            double z = (i & 4) == 0 ? shipLocalBounds.minZ : shipLocalBounds.maxZ;
            if (i >= 8) {
                x = (shipLocalBounds.minX + shipLocalBounds.maxX) * .5;
                y = (shipLocalBounds.minY + shipLocalBounds.maxY) * .5;
                z = (shipLocalBounds.minZ + shipLocalBounds.maxZ) * .5;
                if (i < 14) {
                    int axis = (i - 8) / 2;
                    boolean upper = (i & 1) == 1;
                    if (axis == 0) x = upper ? shipLocalBounds.maxX : shipLocalBounds.minX;
                    if (axis == 1) y = upper ? shipLocalBounds.maxY : shipLocalBounds.minY;
                    if (axis == 2) z = upper ? shipLocalBounds.maxZ : shipLocalBounds.minZ;
                }
            }

            Vector3d oldPoint = previousShipToWorld.transformPosition(new Vector3d(x, y, z));
            Vector3d newPoint = currentShipToWorld.transformPosition(new Vector3d(x, y, z));
            Vec3 before = new Vec3(oldPoint.x, oldPoint.y, oldPoint.z);
            Vec3 after = new Vec3(newPoint.x, newPoint.y, newPoint.z);
            if (!finite(before) || !finite(after)) {
                return new Result(Status.APERTURE_MISS, crossings, misses + 1);
            }
            double previousDistance = before.subtract(portalOrigin).dot(n);
            double currentDistance = after.subtract(portalOrigin).dot(n);
            if (previousDistance > EPSILON && currentDistance <= EPSILON) {
                crossings++;
                if (!crossesActualAperture.test(before, after)) misses++;
            }
        }
        if (misses > 0) return new Result(Status.APERTURE_MISS, crossings, misses);
        return new Result(crossings > 0 ? Status.CANDIDATE : Status.NO_CROSSING,
            crossings, misses);
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static boolean valid(AABB aabb) {
        return Double.isFinite(aabb.minX) && Double.isFinite(aabb.minY)
            && Double.isFinite(aabb.minZ) && Double.isFinite(aabb.maxX)
            && Double.isFinite(aabb.maxY) && Double.isFinite(aabb.maxZ)
            && aabb.maxX >= aabb.minX && aabb.maxY >= aabb.minY
            && aabb.maxZ >= aabb.minZ;
    }
}
