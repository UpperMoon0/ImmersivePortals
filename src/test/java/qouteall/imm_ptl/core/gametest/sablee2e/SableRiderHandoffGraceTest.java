package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SableRiderHandoffGraceTest {
    private static final UUID SEAT = UUID.fromString("4f400c31-6b22-4c22-b1a2-dd51751ef567");

    @Test
    void validSeatNeedsNoHandoff() {
        assertFalse(SableRiderHandoffGrace.verifySourceSeat(SEAT, SEAT, false, false, 0, 120));
    }

    @Test
    void onlyActiveStagedHandoffCanWaitForTheSameSeat() {
        assertTrue(SableRiderHandoffGrace.verifySourceSeat(SEAT, null, true, true, 1, 120));
        assertTrue(SableRiderHandoffGrace.verifySourceSeat(SEAT, null, true, true, 120, 120));
        assertFalse(SableRiderHandoffGrace.verifySourceSeat(SEAT, SEAT, true, true, 0, 120));
    }

    @Test
    void genuineLostSeatWithoutHandoffFailsImmediately() {
        var error = assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifySourceSeat(SEAT, null, false, true, 1, 120));
        assertTrue(error.getMessage().contains("correlatedHandoff=false"));
        assertTrue(error.getMessage().contains(SEAT.toString()));
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifySourceSeat(SEAT, null, true, false, 1, 120));
    }

    @Test
    void stalledHandoffTimesOutAndChangedSeatIsNeverAccepted() {
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifySourceSeat(SEAT, null, true, true, 121, 120));
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifySourceSeat(SEAT, UUID.randomUUID(), true, true, 1, 120));
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifySourceSeat(null, null, true, true, 1, 120));
    }

    @Test
    void onlyTheExpectedSeatAndDimensionPairAreCorrelated() {
        assertTrue(SableRiderHandoffGrace.matchesHandoff(SEAT, SEAT, "source", "source", "target", "target"));
        assertFalse(SableRiderHandoffGrace.matchesHandoff(SEAT, UUID.randomUUID(), "source", "source", "target", "target"));
        assertFalse(SableRiderHandoffGrace.matchesHandoff(SEAT, SEAT, "source", "target", "target", "source"));
        assertFalse(SableRiderHandoffGrace.matchesHandoff(SEAT, null, "source", "source", "target", "target"));
        assertFalse(SableRiderHandoffGrace.matchesHandoff(SEAT, SEAT, null, null, "target", "target"));
    }

    @Test
    void stagedCopiesMayAwaitTheCorrelatedAckWithinTheExistingDetachedBound() {
        int staleTicks = 0;
        for (int detachedTicks = 1; detachedTicks <= 120; detachedTicks++) {
            boolean detached = SableRiderHandoffGrace.verifySourceSeat(
                SEAT, null, true, true, detachedTicks, 120
            );
            staleTicks = SableRiderHandoffGrace.verifyOverlap(
                true, detached, "awaiting-authoritative-ack", detachedTicks, 120, staleTicks, 20
            );
        }
        assertEquals(0, staleTicks);
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifySourceSeat(SEAT, null, true, true, 121, 120));
    }

    @Test
    void ackStartsTheUnchangedStaleCopyDeadlineEvenBeforeSeatReattachment() {
        int staleTicks = 0;
        for (int tick = 1; tick <= 20; tick++) {
            staleTicks = SableRiderHandoffGrace.verifyOverlap(
                true, true, "awaiting-destination-seat", tick, 120, staleTicks, 20
            );
            assertEquals(tick, staleTicks);
        }
        var error = assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifyOverlap(
                true, true, "awaiting-destination-seat", 21, 120, 20, 20
            ));
        assertTrue(error.getMessage().contains("staleOverlapTicks=21/20"));
        assertTrue(error.getMessage().contains("handoffPhase=awaiting-destination-seat"));
    }

    @Test
    void unrelatedCompletedOrExpiredHandoffsCannotHideLeakedCopies() {
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifyOverlap(
                true, false, "awaiting-authoritative-ack", 21, 120, 20, 20
            ));
        for (String phase : new String[] {null, "completed", "unknown"}) {
            assertThrows(IllegalStateException.class,
                () -> SableRiderHandoffGrace.verifyOverlap(true, true, phase, 21, 120, 20, 20));
        }
        for (int detachedTicks : new int[] {0, 121}) {
            assertThrows(IllegalStateException.class,
                () -> SableRiderHandoffGrace.verifyOverlap(
                    true, true, "awaiting-authoritative-ack", detachedTicks, 120, 20, 20
                ));
        }
    }

    @Test
    void aPendingAckCannotResetStaleTicksAndOnlyRetirementRestartsTheDeadline() {
        assertEquals(20, SableRiderHandoffGrace.verifyOverlap(
            true, true, "awaiting-authoritative-ack", 30, 120, 20, 20
        ));
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifyOverlap(true, false, null, 0, 120, 20, 20));
        assertEquals(0, SableRiderHandoffGrace.verifyOverlap(false, false, null, 0, 120, 20, 20));
        assertEquals(1, SableRiderHandoffGrace.verifyOverlap(true, false, null, 0, 120, 0, 20));
    }


    @Test
    void coordinateFrameAmbiguityCannotMaskAnArbitrarySpatialJump() {
        assertDoesNotThrow(() -> SableRiderHandoffGrace.verifySpatialDistance(179.75, 2, true, 32));
        assertDoesNotThrow(() -> SableRiderHandoffGrace.verifySpatialDistance(2, 179.75, true, 32));
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifySpatialDistance(179.75, 180, true, 32));
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifySpatialDistance(179.75, 2, false, 32));
        assertThrows(IllegalStateException.class,
            () -> SableRiderHandoffGrace.verifySpatialDistance(Double.NaN, 2, true, 32));
    }

}
