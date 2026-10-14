package qouteall.imm_ptl.core.compat.sable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableRiderPortalApproachTest {
    @Test void cleanupCompletedStaleReturnCannotReverseAnOutwardMovingBody() {
        // Nether ceiling -> Overworld floor: the body is travelling upward into Overworld.
        // A stale client rider sample below Y=-64 must not authorize the opposite floor portal.
        assertFalse(approachesFloor(38, 0, 0));
    }

    @Test void firstForwardRequestAndImmediateRealReverseMotionAreAllowed() {
        assertTrue(approachesFloor(-38, 0, 0));
        assertTrue(approachesFloor(-1.0e-12, 0, 0), "There is no arbitrary low-speed cutoff");
        assertTrue(SableRiderPortalApproach.movesIntoPortal(
            0, 38, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0
        ), "An upward approach enters a ceiling portal");
    }

    @Test void rotationCanMoveTheSeatInwardWhileTheCenterOfMassMovesOutward() {
        // omega=(0,0,-2), offset=(3,0,0) gives seat velocity (0,-6,0).
        assertTrue(approachesFloor(4, -2, 3));
        assertTrue(approachesFloor(0, -2, 3));
        assertFalse(approachesFloor(-4, 2, 3), "Outward seat rotation can exceed inward COM motion");
        assertFalse(approachesFloor(-6, 2, 3), "Exact cancellation is stationary at the seat");
    }

    @Test void rollCanCarryTheMountedEyeInwardWhileSeatAndComDoNotApproach() {
        // Static +X plane: omega=(0,0,2), eye one block above COM.
        assertFalse(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 0, 0, 2, 0, 0, 0, 1, 0, 0));
        assertTrue(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 0, 0, 2, 0, 1, 0, 1, 0, 0));
        // Seat is below COM and moves outward; its eye is above COM and moves inward.
        assertFalse(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 0, 0, 2, 0, -0.5, 0, 1, 0, 0));
        assertTrue(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 0, 0, 2, 0, 0.5, 0, 1, 0, 0));
    }

    @Test void inwardSeatRotationCannotAuthorizeAnOutwardMountedEye() {
        assertTrue(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 0, 0, -2, 0, -0.5, 0, 1, 0, 0));
        assertFalse(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 0, 0, -2, 0, 0.5, 0, 1, 0, 0));
    }

    @Test void pitchUsesEyeOffsetAndPreservesRelativePortalMotion() {
        // omega=(2,0,0), eye offset +Y: eye moves +Z into a -Z-facing plane.
        assertFalse(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, -1));
        assertTrue(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, -1));
        assertFalse(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, -1, 0, 0, 0.1),
            "A plane matching the eye's two blocks/second does not approach");
    }

    @Test void allAngularAxesAndPortalNormalComponentsAreUsed() {
        assertTrue(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 2, 0, 0, 0, 0, 3, 0, 1, 0
        ));
        assertTrue(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 0, 2, 0, 3, 0, 0, 0, 0, 1
        ));
        assertTrue(SableRiderPortalApproach.movesIntoPortal(
            0, 0, 0, 0, 0, 2, 0, 3, 0, 1, 0, 0
        ));
    }

    @Test void tangentialStationaryAndOutwardMotionAreRejected() {
        assertFalse(approachesFloor(0, 0, 0));
        assertFalse(approachesFloor(-0.0, 0, 0));
        assertFalse(SableRiderPortalApproach.movesIntoPortal(
            8, 0, 4, 0, 0, 0, 3, 1, 2, 0, 1, 0
        ));
        assertFalse(SableRiderPortalApproach.movesIntoPortal(
            0, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        ));
    }

    @Test void nonFiniteNativeValuesCannotAuthorizeMigration() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (int component = 0; component < 12; component++) {
                double[] args = {0, -38, 0, 0, 0, 0, 3, 1, 2, 0, 1, 0};
                args[component] = invalid;
                assertFalse(SableRiderPortalApproach.movesIntoPortal(
                    args[0], args[1], args[2], args[3], args[4], args[5],
                    args[6], args[7], args[8], args[9], args[10], args[11]
                ), "Invalid component " + component + ": " + invalid);
            }
        }
    }

    @Test void sourcePlaneMotionIsRelativeAndConvertedFromBlocksPerTick() {
        assertTrue(relativeFloorApproach(0, .1), "A moving floor plane can sweep a stationary seat");
        assertTrue(relativeFloorApproach(1, .1), ".1 blocks/tick is 2 m/s, faster than this outward body");
        assertFalse(relativeFloorApproach(3, .1), "A body outrunning the plane is still moving away");
        assertFalse(relativeFloorApproach(2, .1), "A co-moving seat does not approach the plane");
        assertFalse(relativeFloorApproach(0, -.1), "A receding plane cannot authorize crossing");
        assertTrue(relativeFloorApproach(0, 1.0e-12), "Slow portal sweeps have no arbitrary cutoff");
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(relativeFloorApproach(-1, invalid));
        }
    }

    private static boolean relativeFloorApproach(double linearY, double portalPointYPerTick) {
        return SableRiderPortalApproach.movesIntoPortal(
            0, linearY, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, portalPointYPerTick, 0
        );
    }

    private static boolean approachesFloor(double linearY, double angularZ, double offsetX) {
        return SableRiderPortalApproach.movesIntoPortal(
            0, linearY, 0, 0, 0, angularZ, offsetX, 0, 0, 0, 1, 0
        );
    }
}
