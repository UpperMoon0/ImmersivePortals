package qouteall.imm_ptl.core.compat.sable;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SableStandingMovementHoldTest {
    @Test void heldSourceSnapshotSurvivesBodyMotionButNotAnotherFrameOrPlayer() {
        Object player = new Object();
        var hold = SableRiderMovementHold.standing(player, "source", "destination", 100);
        // Travel is held while the independently interpolated platform advances 1.318 blocks.
        double originalFeet = 5;
        double frozenPlayerY = 105;
        double movedBodyY = 101.318;
        assertTrue(Math.abs((frozenPlayerY - movedBodyY) - originalFeet) > 1);
        assertTrue(hold.ownsStandingSnapshot(player, "source", "destination"));
        assertFalse(hold.ownsStandingSnapshot(new Object(), "source", "destination"));
        assertFalse(hold.ownsStandingSnapshot(player, "other", "destination"));
        assertFalse(hold.ownsStandingSnapshot(player, "source", "other"));
        hold.cancel();
        assertFalse(hold.ownsStandingSnapshot(player, "source", "destination"));
    }
    @Test void expiredInvalidatedOrMountedHoldCannotReuseStandingSnapshot() {
        Object player = new Object();
        var expired = SableRiderMovementHold.standing(player, "source", "destination", 1);
        expired.tick();
        assertFalse(expired.ownsStandingSnapshot(player, "source", "destination"));
        var invalidated = SableRiderMovementHold.standing(player, "source", "destination", 100);
        invalidated.shouldHold(player, UUID.randomUUID(), "source");
        assertFalse(invalidated.ownsStandingSnapshot(player, "source", "destination"));
        var mounted = new SableRiderMovementHold(player, UUID.randomUUID(), "source", "destination", 100);
        assertFalse(mounted.ownsStandingSnapshot(player, "source", "destination"));
    }
    @Test void standingWaitsAcrossSourceAndDestinationUntilTerminalAck() {
        Object player=new Object();
        var hold=SableRiderMovementHold.standing(player,"source","destination",100);
        for(int i=0;i<40;i++){assertTrue(hold.shouldHold(player,null,"source"));hold.tick();}
        assertTrue(hold.shouldHold(player,null,"destination"));
        hold.cancel();
        assertFalse(hold.shouldHold(player,null,"destination"));
    }
    @Test void standingExpiryCannotStrandMovement() {
        Object player=new Object();
        var hold=SableRiderMovementHold.standing(player,"source","destination",100);
        for(int i=0;i<100;i++){assertTrue(hold.shouldHold(player,null,"source"));hold.tick();}
        assertTrue(hold.expired());assertFalse(hold.shouldHold(player,null,"source"));
    }
    @Test void replacementMountAndUnrelatedDimensionInvalidatePermanently() {
        Object player=new Object();
        for(int scenario=0;scenario<3;scenario++){
            var hold=SableRiderMovementHold.standing(player,"source","destination",100);
            assertFalse(hold.shouldHold(scenario==0?new Object():player,scenario==1?UUID.randomUUID():null,
                scenario==2?"third":"source"));
            assertTrue(hold.invalidated());
            assertFalse(hold.shouldHold(player,null,"source"));
        }
    }
}
