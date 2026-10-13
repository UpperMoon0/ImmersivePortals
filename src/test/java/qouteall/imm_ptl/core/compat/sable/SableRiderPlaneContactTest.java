package qouteall.imm_ptl.core.compat.sable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableRiderPlaneContactTest {
    @Test void frontPlaneBehindSweepRequiresTwoContinuousAuthoritativeIntervals() {
        var contact = new SableRiderPlaneContact(5);
        Object front = new Object(), plane = new Object(), behind = new Object();

        assertFalse(contact.sample(front, plane, 100, 1, 0, true),
            "Reaching the plane records contact but has not crossed behind it");
        assertTrue(contact.isArmed());
        assertTrue(contact.sample(plane, behind, 101, 0, -1, true));
        assertFalse(contact.isArmed(), "The completed crossing consumes the contact");
    }

    @Test void ordinaryDirectCrossingIsLeftToThePortalShapeRayTrace() {
        var contact = new SableRiderPlaneContact(5);
        assertFalse(contact.sample(new Object(), new Object(), 100, 1, -1, true));
        assertFalse(contact.isArmed());
    }

    @Test void eitherSignedZeroRepresentsTheExactPlane() {
        for (double contactZero : new double[] {0.0, -0.0}) {
            for (double departureZero : new double[] {0.0, -0.0}) {
                var contact = new SableRiderPlaneContact(5);
                Object plane = new Object();
                assertFalse(contact.sample(new Object(), plane, 100, 1, contactZero, true));
                assertTrue(contact.isArmed());
                assertTrue(contact.sample(plane, new Object(), 101, departureZero, -1, true));
            }
        }
    }

    @Test void arbitrarilySmallGenuineFrontAndBehindDistancesRetainTheirSigns() {
        for (double distance : new double[] {Double.MIN_VALUE, Double.MIN_NORMAL, 1.0e-12}) {
            var contact = new SableRiderPlaneContact(5);
            Object plane = new Object();
            assertFalse(contact.sample(new Object(), plane, 100, distance, 0, true));
            assertTrue(contact.isArmed(), "No minimum approach distance: " + distance);
            assertTrue(contact.sample(plane, new Object(), 101, 0, -distance, true),
                "No minimum penetration distance: " + distance);
        }
    }

    @Test void nearbyNonzeroDistancesAreNeverRoundedOntoThePlane() {
        for (double distance : new double[] {Double.MIN_VALUE, Double.MIN_NORMAL, 1.0e-12}) {
            for (double nearPlane : new double[] {distance, -distance}) {
                var contact = new SableRiderPlaneContact(5);
                assertFalse(contact.sample(new Object(), new Object(), 100, 1, nearPlane, true));
                assertFalse(contact.isArmed(), "Contact must be exactly zero: " + nearPlane);
            }
            var contact = new SableRiderPlaneContact(5);
            Object plane = new Object();
            assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
            assertFalse(contact.sample(plane, new Object(), 101, distance, -distance, true),
                "A positive-to-negative segment belongs to the ordinary crossing path");
            assertFalse(contact.isArmed());
        }
    }

    @Test void initialMountAtPlaneCannotInventEarlierFrontSideHistory() {
        var contact = new SableRiderPlaneContact(5);
        Object plane = new Object(), nextPlane = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 0, 0, true));
        assertFalse(contact.isArmed());
        assertFalse(contact.sample(plane, nextPlane, 101, 0, 0, true));
        assertFalse(contact.sample(nextPlane, new Object(), 102, 0, -1, true));
        assertFalse(contact.isArmed());
    }

    @Test void alreadyBehindAndReverseMotionCannotArmAContact() {
        for (double[] distances : new double[][] {{-1, -2}, {-1, 0}, {-1, 1}, {0, 1}}) {
            var contact = new SableRiderPlaneContact(5);
            Object after = new Object();
            assertFalse(contact.sample(new Object(), after, 100, distances[0], distances[1], true));
            assertFalse(contact.isArmed());
            assertFalse(contact.sample(after, new Object(), 101, 0, -1, true));
        }
    }

    @Test void returningToFrontClearsContactUntilAnotherGenuineApproach() {
        var contact = new SableRiderPlaneContact(5);
        Object plane = new Object(), front = new Object(), laterPlane = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
        assertFalse(contact.sample(plane, front, 101, 0, 1, true));
        assertFalse(contact.isArmed());
        assertFalse(contact.sample(front, laterPlane, 102, 1, 0, true));
        assertTrue(contact.isArmed(), "A fresh authoritative front-side approach can rearm");
        assertTrue(contact.sample(laterPlane, new Object(), 103, 0, -1, true));
    }

    @Test void initialContactOutsideApertureCannotAuthorizeLaterEntry() {
        var contact = new SableRiderPlaneContact(5);
        Object plane = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, false));
        assertFalse(contact.isArmed());
        assertFalse(contact.sample(plane, new Object(), 101, 0, -1, true));
    }

    @Test void leavingApertureWhileOnPlaneClearsPriorContact() {
        var contact = new SableRiderPlaneContact(5);
        Object plane = new Object(), outside = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
        assertFalse(contact.sample(plane, outside, 101, 0, 0, false));
        assertFalse(contact.isArmed());
        assertFalse(contact.sample(outside, new Object(), 102, 0, -1, true));
    }

    @Test void departureOutsideApertureRejectsAndConsumesPriorContact() {
        var contact = new SableRiderPlaneContact(5);
        Object plane = new Object(), outside = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
        assertFalse(contact.sample(plane, outside, 101, 0, -1, false));
        assertFalse(contact.isArmed());
        assertFalse(contact.sample(outside, new Object(), 102, 0, -1, true));
    }

    @Test void continuousPlaneSamplesPreserveContactThroughItsInclusiveLifetime() {
        var contact = new SableRiderPlaneContact(3);
        Object current = new Object();
        assertFalse(contact.sample(new Object(), current, 100, 1, 0, true));
        for (long tick = 101; tick < 103; tick++) {
            Object next = new Object();
            assertFalse(contact.sample(current, next, tick, 0, 0, true));
            assertTrue(contact.isArmed());
            current = next;
        }
        assertTrue(contact.sample(current, new Object(), 103, 0, -1, true));
    }

    @Test void repeatedPlaneSamplesCannotRefreshTheOriginalContactLifetime() {
        var contact = new SableRiderPlaneContact(2);
        Object current = new Object();
        assertFalse(contact.sample(new Object(), current, 100, 1, 0, true));
        for (long tick = 101; tick <= 102; tick++) {
            Object next = new Object();
            assertFalse(contact.sample(current, next, tick, 0, 0, true));
            assertTrue(contact.isArmed());
            current = next;
        }
        assertFalse(contact.sample(current, new Object(), 103, 0, -1, true));
        assertFalse(contact.isArmed());
    }

    @Test void zeroLifetimeCannotAuthorizeASeparateIncreasingTick() {
        var contact = new SableRiderPlaneContact(0);
        Object plane = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
        assertTrue(contact.isArmed());
        assertFalse(contact.sample(plane, new Object(), 101, 0, -1, true));
        assertFalse(contact.isArmed());
    }

    @Test void expiryQueryIsReadOnlyAndUsesTheOriginalInclusiveWindow() {
        var contact = new SableRiderPlaneContact(2);
        assertTrue(contact.expired(100), "An unarmed helper has no contact to retain");
        Object plane = new Object(), nextPlane = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
        assertTrue(contact.expired(99), "A reversed clock is outside the contact window");
        assertFalse(contact.expired(100));
        assertFalse(contact.expired(102));
        assertTrue(contact.expired(103));
        assertTrue(contact.isArmed(), "Checking expiry must not mutate sample history");
        assertFalse(contact.sample(plane, nextPlane, 101, 0, 0, true));
        assertFalse(contact.expired(102));
        assertTrue(contact.expired(103), "A retained zero sample does not extend the window");
        assertTrue(contact.sample(nextPlane, new Object(), 102, 0, -1, true));
        assertTrue(contact.expired(102));
    }

    @Test void missingAuthoritativeStateContinuityClearsContact() {
        var contact = new SableRiderPlaneContact(5);
        Object plane = new Object(), unrelatedBefore = new Object(), after = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
        assertFalse(contact.sample(unrelatedBefore, after, 101, 0, -1, true));
        assertFalse(contact.isArmed());
        assertFalse(contact.sample(after, new Object(), 102, 0, -1, true));
    }

    @Test void equalButDistinctStatesDoNotEstablishIdentityContinuity() {
        var contact = new SableRiderPlaneContact(5);
        Object plane = new String("same state value");
        Object equalState = new String("same state value");
        assertTrue(plane.equals(equalState));
        assertFalse(plane == equalState);
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
        assertFalse(contact.sample(equalState, new Object(), 101, 0, -1, true));
        assertFalse(contact.isArmed());
    }

    @Test void duplicateOrBackwardSampleTicksCannotAuthorizeOrPreserveContact() {
        for (long invalidTick : new long[] {99, 100}) {
            var contact = new SableRiderPlaneContact(5);
            Object plane = new Object(), after = new Object();
            assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
            assertFalse(contact.sample(plane, after, invalidTick, 0, -1, true));
            assertFalse(contact.isArmed());
            assertFalse(contact.sample(after, new Object(), 101, 0, -1, true));
        }
    }

    @Test void sampleTicksMustAdvancePastTheLatestRetainedPlaneSample() {
        var contact = new SableRiderPlaneContact(5);
        Object plane = new Object(), nextPlane = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
        assertFalse(contact.sample(plane, nextPlane, 101, 0, 0, true));
        assertFalse(contact.sample(nextPlane, new Object(), 101, 0, -1, true),
            "Being later than the first contact is insufficient if the latest sample is duplicated");
        assertFalse(contact.isArmed());
    }

    @Test void skippedTicksBreakContactEvenWhenThePortalStateIdentityIsFrozen() {
        for (double[] distances : new double[][] {{0, -1}, {0, 0}}) {
            var contact = new SableRiderPlaneContact(5);
            Object frozenState = new Object();
            assertFalse(contact.sample(new Object(), frozenState, 100, 1, 0, true));
            assertFalse(contact.sample(frozenState, frozenState, 102, distances[0], distances[1], true),
                "The unobserved tick may have left the aperture despite identical portal states");
            assertFalse(contact.isArmed(), "A skipped interval cannot preserve earlier contact");
            assertFalse(contact.sample(frozenState, new Object(), 103, 0, -1, true));
        }
    }

    @Test void duplicateOrBackwardTicksRejectEvenFreshFrontContact() {
        for (long invalidTick : new long[] {99, 100}) {
            var contact = new SableRiderPlaneContact(5);
            Object plane = new Object(), discontinuousPlane = new Object(), freshPlane = new Object();
            assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
            assertFalse(contact.sample(plane, discontinuousPlane, invalidTick, 1, 0, true));
            assertFalse(contact.isArmed(), "Reject the complete discontinuous sample");
            assertFalse(contact.sample(discontinuousPlane, freshPlane, 101, 1, 0, true));
            assertTrue(contact.isArmed(), "A later independent front contact may rearm");
            assertTrue(contact.sample(freshPlane, new Object(), 102, 0, -1, true));
        }
    }

    @Test void forwardGapExpiryAndNewStateCanRearmFromCurrentFrontContact() {
        long[] freshContactTicks = {102, 106, 101};
        for (int scenario = 0; scenario < freshContactTicks.length; scenario++) {
            var contact = new SableRiderPlaneContact(5);
            Object plane = new Object(), freshPlane = new Object();
            long freshTick = freshContactTicks[scenario];
            assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
            assertFalse(contact.sample(scenario == 2 ? new Object() : plane, freshPlane,
                freshTick, 1, 0, true));
            assertTrue(contact.isArmed(),
                "Fresh front-to-plane proof starts new history after a gap, expiry or changed state");
            assertFalse(contact.expired(freshTick + 5), "The fresh contact gets its own lifetime");
            assertTrue(contact.sample(freshPlane, new Object(), freshTick + 1, 0, -1, true));
        }
    }

    @Test void nonFiniteDistancesCannotArmOrPreserveContact() {
        for (double invalid : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            for (double[] distances : new double[][] {{invalid, 0}, {1, invalid}, {0, invalid}, {invalid, -1}}) {
                var initial = new SableRiderPlaneContact(5);
                assertFalse(initial.sample(new Object(), new Object(), 100, distances[0], distances[1], true));
                assertFalse(initial.isArmed());

                var armed = new SableRiderPlaneContact(5);
                Object plane = new Object(), invalidState = new Object();
                assertFalse(armed.sample(new Object(), plane, 100, 1, 0, true));
                assertFalse(armed.sample(plane, invalidState, 101, distances[0], distances[1], true));
                assertFalse(armed.isArmed());
                assertFalse(armed.sample(invalidState, new Object(), 102, 0, -1, true));
            }
        }
    }

    @Test void nullAuthoritativeStatesCannotArmOrPreserveContact() {
        for (boolean missingBefore : new boolean[] {false, true}) {
            Object state = new Object();
            var initial = new SableRiderPlaneContact(5);
            assertFalse(initial.sample(missingBefore ? null : state, missingBefore ? state : null,
                100, 1, 0, true));
            assertFalse(initial.isArmed());

            var armed = new SableRiderPlaneContact(5);
            assertFalse(armed.sample(new Object(), state, 100, 1, 0, true));
            assertFalse(armed.sample(missingBefore ? null : state, missingBefore ? state : null,
                101, 0, -1, true));
            assertFalse(armed.isArmed());
            assertFalse(armed.sample(state, new Object(), 102, 0, -1, true));
        }
    }

    @Test void consumedContactCannotAuthorizeARepeatedOrDelayedCrossing() {
        var contact = new SableRiderPlaneContact(5);
        Object plane = new Object(), behind = new Object();
        assertFalse(contact.sample(new Object(), plane, 100, 1, 0, true));
        assertTrue(contact.sample(plane, behind, 101, 0, -1, true));
        assertFalse(contact.sample(plane, behind, 101, 0, -1, true));
        assertFalse(contact.sample(behind, new Object(), 102, 0, -1, true));
        assertFalse(contact.isArmed());
    }
}
