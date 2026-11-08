package qouteall.imm_ptl.core.compat.valkyrienskies;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.compat.valkyrienskies.ShipPortalSweep.Status.*;

class ShipPortalSweepTest {
    private static final Vec3 ORIGIN = Vec3.ZERO;
    private static final Vec3 NORMAL = new Vec3(1, 0, 0);
    private static final AABB HULL = new AABB(-2, -1, -3, 2, 1, 3);

    private ShipPortalSweep.Result sweep(double fromX, double toX, AABB hull,
                                         java.util.function.BiPredicate<Vec3, Vec3> aperture) {
        return ShipPortalSweep.evaluate(new Matrix4d().translation(fromX, 0, 0),
            new Matrix4d().translation(toX, 0, 0), hull, ORIGIN, NORMAL, aperture);
    }

    @Test
    void translatesLongHullThroughThePlaneInsteadOfTestingOnlyCom() {
        var result = sweep(2.5, 1.5, HULL, (a, b) -> true);
        assertEquals(CANDIDATE, result.status());
        assertTrue(result.crossedSamples() > 0);
        assertTrue(result.canBeginTransfer());
    }

    @Test
    void stationaryAndReverseAndNoInitialHistoryCannotTrigger() {
        assertEquals(NO_CROSSING, sweep(5, 5, HULL, (a, b) -> true).status());
        assertEquals(NO_CROSSING, sweep(-5, -6, HULL, (a, b) -> true).status());
        assertEquals(NO_CROSSING, sweep(-1, 2, HULL, (a, b) -> true).status());
    }

    @Test
    void singleMissedCornerRejectsWholeShipEvenIfOtherCornersFit() {
        var result = sweep(2.5, 1.5, HULL, (a, b) -> a.y < 0.5);
        assertEquals(APERTURE_MISS, result.status());
        assertTrue(result.crossedSamples() > result.rejectedSamples());
        assertFalse(result.canBeginTransfer());
    }

    @Test
    void faceCenterMissAlsoRejectsCrossing() {
        var result = sweep(2.5, 1.5, HULL, (a, b) -> Math.abs(a.y) > 0.01);
        assertEquals(APERTURE_MISS, result.status());
        assertTrue(result.rejectedSamples() > 0);
    }

    @Test
    void rotatedHullCrossesAndMissesCanBeDetected() {
        Matrix4d oldPose = new Matrix4d().translation(2, 0, 0).rotateZ(Math.PI / 4);
        Matrix4d newPose = new Matrix4d().translation(-3, 0, 0).rotateZ(Math.PI / 4);
        var result = ShipPortalSweep.evaluate(oldPose, newPose, HULL,
            ORIGIN, NORMAL, (a, b) -> Math.abs(a.z) < 2);
        assertEquals(APERTURE_MISS, result.status());
    }

    @Test
    void nonFiniteTransformsFailClosed() {
        Matrix4d invalid = new Matrix4d().translation(Double.NaN, 0, 0);
        var result = ShipPortalSweep.evaluate(invalid, new Matrix4d(), HULL,
            ORIGIN, NORMAL, (a, b) -> true);
        assertFalse(result.canBeginTransfer());
    }

    @Test
    void exactContactWithoutPriorFrontApproachDoesNotStartHandoff() {
        var result = sweep(0, -1, new AABB(0, 0, 0, 0, 0, 0), (a, b) -> true);
        assertEquals(NO_CROSSING, result.status());
    }
}
