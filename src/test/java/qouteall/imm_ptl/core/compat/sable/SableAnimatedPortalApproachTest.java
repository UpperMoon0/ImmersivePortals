package qouteall.imm_ptl.core.compat.sable;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.portal.PortalState;
import qouteall.imm_ptl.core.teleportation.TeleportationUtil;
import qouteall.q_misc_util.my_util.DQuaternion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableAnimatedPortalApproachTest {
    // UnilateralPortalState builds its orientation matrix from a float quaternion.
    private static final double POSITION_EPSILON = 1.0e-6;
    private static final Vec3 SOURCE = new Vec3(10, 64, -20);
    private static final Vec3 DESTINATION = new Vec3(100, 70, -50);
    private static final Vec3 OFF_CENTER = new Vec3(2, 3, 0);

    @Test void translatingSourcePlaneUsesRelativeNativeVelocityInSeconds() {
        PortalState before = state(SOURCE, DESTINATION, DQuaternion.identity, DQuaternion.identity, 1);
        PortalState after = state(
            SOURCE.add(0, 0, 0.125), DESTINATION, DQuaternion.identity, DQuaternion.identity, 1
        );
        var velocity = TeleportationUtil.getPortalPointVelocity(before, after, OFF_CENTER);

        assertVector(new Vec3(0, 0, 0.125), velocity.thisSidePointVelocity());
        assertVector(Vec3.ZERO, velocity.otherSidePointVelocity());
        assertTrue(approaches(after, velocity, Vec3.ZERO), "A source plane can sweep over a stationary seat");
        assertTrue(approaches(after, velocity, new Vec3(0, 0, 1)),
            "The portal advances at 2.5 blocks/second, overtaking a seat moving outward at 1");
        assertFalse(approaches(after, velocity, new Vec3(0, 0, 2.5)),
            "Matching the plane velocity has no inward relative motion");
        assertFalse(approaches(after, velocity, new Vec3(0, 0, 3)),
            "A seat moving outward faster than the plane is still rejected");
    }

    @Test void recedingSourcePlaneCannotAuthorizeASeatThatFailsToCatchIt() {
        PortalState before = state(SOURCE, DESTINATION, DQuaternion.identity, DQuaternion.identity, 1);
        PortalState after = state(
            SOURCE.add(0, 0, -0.125), DESTINATION, DQuaternion.identity, DQuaternion.identity, 1
        );
        var velocity = TeleportationUtil.getPortalPointVelocity(before, after, OFF_CENTER);

        assertVector(new Vec3(0, 0, -0.125), velocity.thisSidePointVelocity());
        assertFalse(approaches(after, velocity, Vec3.ZERO));
        assertFalse(approaches(after, velocity, new Vec3(0, 0, -1)),
            "World-space inward motion alone cannot authorize a receding portal crossing");
        assertFalse(approaches(after, velocity, new Vec3(0, 0, -2.5)));
        assertTrue(approaches(after, velocity, new Vec3(0, 0, -3)),
            "A seat that actually catches the receding plane may cross");
    }

    @Test void rotatingSourcePlaneUsesTheSeatPointInsteadOfThePortalCenter() {
        PortalState before = state(SOURCE, DESTINATION, DQuaternion.identity, DQuaternion.identity, 1);
        PortalState after = state(
            SOURCE, DESTINATION,
            DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), 90), DQuaternion.identity, 1
        );
        var approachingEdge = TeleportationUtil.getPortalPointVelocity(before, after, new Vec3(-2, 0, 0));
        var recedingEdge = TeleportationUtil.getPortalPointVelocity(before, after, new Vec3(2, 0, 0));
        var center = TeleportationUtil.getPortalPointVelocity(before, after, Vec3.ZERO);

        assertVector(new Vec3(1, 0, 0), after.orientation.getNormal());
        assertVector(new Vec3(2, 0, 2), approachingEdge.thisSidePointVelocity());
        assertVector(new Vec3(-2, 0, -2), recedingEdge.thisSidePointVelocity());
        assertVector(Vec3.ZERO, center.thisSidePointVelocity());
        assertTrue(approaches(after, approachingEdge, Vec3.ZERO));
        assertFalse(approaches(after, recedingEdge, Vec3.ZERO));
        assertFalse(approaches(after, center, Vec3.ZERO), "Rotation leaves the portal center stationary");
        assertTrue(approaches(after, approachingEdge, new Vec3(10, 0, 0)),
            "The off-center point can overtake a seat moving along the current outward normal");
        assertFalse(approaches(after, approachingEdge, new Vec3(41, 0, 0)));
        assertFalse(approaches(after, approachingEdge, approachingEdge.thisSidePointVelocity().scale(20)),
            "Matching the complete rotating-point velocity is stationary relative to the plane");
    }

    @Test void destinationTranslationDoesNotMoveTheSourcePlane() {
        PortalState before = state(SOURCE, DESTINATION, DQuaternion.identity, DQuaternion.identity, 1);
        PortalState after = state(
            SOURCE, DESTINATION.add(4, -2, 1), DQuaternion.identity, DQuaternion.identity, 1
        );
        var velocity = TeleportationUtil.getPortalPointVelocity(before, after, OFF_CENTER);

        assertVector(new Vec3(4, -2, 1), velocity.otherSidePointVelocity());
        assertStationarySourceDecisions(after, velocity);
    }

    @Test void destinationRotationDoesNotMoveTheSourcePlane() {
        PortalState before = state(SOURCE, DESTINATION, DQuaternion.identity, DQuaternion.identity, 1);
        PortalState after = state(
            SOURCE, DESTINATION, DQuaternion.identity,
            DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), 90), 1
        );
        var velocity = TeleportationUtil.getPortalPointVelocity(before, after, OFF_CENTER);

        assertVector(new Vec3(-2, 0, -2), velocity.otherSidePointVelocity());
        assertStationarySourceDecisions(after, velocity);
    }

    @Test void destinationScalingDoesNotMoveTheSourcePlane() {
        DQuaternion destinationRotation = DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), 90);
        PortalState before = state(SOURCE, DESTINATION, DQuaternion.identity, destinationRotation, 1);
        PortalState after = state(SOURCE, DESTINATION, DQuaternion.identity, destinationRotation, 2);
        var velocity = TeleportationUtil.getPortalPointVelocity(before, after, OFF_CENTER);

        assertVector(new Vec3(0, 3, -2), velocity.otherSidePointVelocity());
        assertStationarySourceDecisions(after, velocity);
    }

    @Test void nativeSeatRotationCombinesWithAnimatedPlaneMotion() {
        PortalState before = state(SOURCE, DESTINATION, DQuaternion.identity, DQuaternion.identity, 1);
        PortalState after = state(
            SOURCE.add(0, 0, 0.125), DESTINATION, DQuaternion.identity, DQuaternion.identity, 1
        );
        var velocity = TeleportationUtil.getPortalPointVelocity(before, after, OFF_CENTER);
        Vec3 linear = new Vec3(0, 0, 4);
        Vec3 seatOffset = new Vec3(3, 0, 0);

        assertTrue(approaches(after, velocity, linear, new Vec3(0, 1, 0), seatOffset),
            "Seat velocity 4 - 3 is slower than the source plane's 2.5 blocks/second");
        assertFalse(approaches(after, velocity, linear, new Vec3(0, 0.5, 0), seatOffset),
            "Seat and plane velocities cancel exactly after the native angular contribution");
        assertFalse(approaches(after, velocity, linear, Vec3.ZERO, seatOffset),
            "Ignoring seat rotation would incorrectly reject the first case");
    }

    private static void assertStationarySourceDecisions(
        PortalState state, TeleportationUtil.PortalPointVelocity velocity
    ) {
        assertVector(Vec3.ZERO, velocity.thisSidePointVelocity());
        assertFalse(approaches(state, velocity, Vec3.ZERO),
            "Destination-only animation cannot make a stationary source-side seat cross");
        assertTrue(approaches(state, velocity, new Vec3(0, 0, -1)));
        assertFalse(approaches(state, velocity, new Vec3(0, 0, 1)));
    }

    private static boolean approaches(
        PortalState state, TeleportationUtil.PortalPointVelocity velocity, Vec3 nativeLinear
    ) {
        return approaches(state, velocity, nativeLinear, Vec3.ZERO, Vec3.ZERO);
    }

    private static boolean approaches(
        PortalState state, TeleportationUtil.PortalPointVelocity velocity,
        Vec3 nativeLinear, Vec3 nativeAngular, Vec3 seatOffset
    ) {
        Vec3 normal = state.orientation.getNormal();
        Vec3 sourcePointVelocity = velocity.thisSidePointVelocity();
        return SableRiderPortalApproach.movesIntoPortal(
            nativeLinear.x, nativeLinear.y, nativeLinear.z,
            nativeAngular.x, nativeAngular.y, nativeAngular.z,
            seatOffset.x, seatOffset.y, seatOffset.z,
            normal.x, normal.y, normal.z,
            sourcePointVelocity.x, sourcePointVelocity.y, sourcePointVelocity.z
        );
    }

    private static PortalState state(
        Vec3 source, Vec3 destination, DQuaternion orientation, DQuaternion rotation, double scale
    ) {
        // These mathematical APIs never inspect dimension keys. Null keys avoid bootstrapping
        // Level and its registries, while exercising the actual production state and transforms.
        return new PortalState(null, source, null, destination, scale, rotation, orientation, 12, 10);
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, POSITION_EPSILON, "x component");
        assertEquals(expected.y, actual.y, POSITION_EPSILON, "y component");
        assertEquals(expected.z, actual.z, POSITION_EPSILON, "z component");
    }
}
