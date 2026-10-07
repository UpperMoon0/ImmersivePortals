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
