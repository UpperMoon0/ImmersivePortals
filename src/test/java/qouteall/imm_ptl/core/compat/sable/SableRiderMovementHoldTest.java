package qouteall.imm_ptl.core.compat.sable;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SableRiderMovementHoldTest {
    private final UUID player = UUID.randomUUID(), seat = UUID.randomUUID();
    private SableRiderMovementHold hold() { return new SableRiderMovementHold(player, seat, "source", "destination", 100); }
    @Test void delayedAcknowledgmentDoesNotTurnTheDetachedRiderIntoAnOnFootFall() {
        var hold = hold(); double y = -62.87, velocity = 0;
        for (int i = 0; i < 40; i++) {
            if (!hold.shouldHold(player, null, "source")) { velocity = (velocity - 0.08) * 0.98; y += velocity; }
            hold.tick();
        }
        assertEquals(-62.87, y);
        assertTrue(hold.shouldHold(player, null, "destination"));
        assertFalse(hold.shouldHold(player, seat, "destination"));
    }
    @Test void initialSourceMountDoesNotConsumeTheHandoff() {
        var hold = hold();
        assertFalse(hold.shouldHold(player, seat, "source"));
        assertTrue(hold.shouldHold(player, null, "source"));
        assertFalse(new SableRiderMovementHold(player, null, "source", "destination", 100).shouldHold(player, null, "source"));
    }
    @Test void unrelatedTeleportPermanentlyInvalidatesRatherThanRearming() {
        var hold = hold();
        assertTrue(hold.shouldHold(player, null, "source"));
        assertFalse(hold.shouldHold(player, null, "third dimension"));
        assertTrue(hold.invalidated());
        assertFalse(hold.shouldHold(player, null, "source"));
        assertFalse(hold.shouldHold(player, null, "destination"));
    }
    @Test void respawnWithTheSameUuidCannotInheritTheOldPlayersHold() {
        var hold = hold();
        UUID replacement = UUID.fromString(player.toString());
        assertEquals(player, replacement);
        assertNotSame(player, replacement);
        assertFalse(hold.shouldHold(replacement, null, "source"));
        assertTrue(hold.invalidated());
        assertFalse(hold.shouldHold(player, null, "source"));
    }
    @Test void unrelatedSeatPermanentlyInvalidatesTheHandoff() {
        var hold = hold();
        assertFalse(hold.shouldHold(player, UUID.randomUUID(), "source"));
        assertTrue(hold.invalidated());
        assertFalse(hold.shouldHold(player, null, "source"));
    }
    @Test void reattachmentThenDismountCannotRearmTheHold() {
        for (String dimension : new String[] {"source", "destination"}) {
            var hold = hold();
            assertTrue(hold.shouldHold(player, null, "source"));
            assertFalse(hold.shouldHold(player, seat, dimension));
            assertTrue(hold.completed());
            assertFalse(hold.invalidated());
            assertFalse(hold.shouldHold(player, null, dimension));
        }
    }
    @Test void destinationMountCompletesEvenIfDetachmentWasBetweenObservations() {
        var hold = hold();
        assertFalse(hold.shouldHold(player, seat, "destination"));
        assertTrue(hold.completed());
        assertFalse(hold.shouldHold(player, null, "destination"));
    }
    @Test void completedMovementRetainsTheBoundedProtocolLifetime() {
        var hold = hold();
        assertTrue(hold.shouldHold(player, null, "source"));
        assertFalse(hold.shouldHold(player, seat, "destination"));
        assertTrue(hold.completed());
        assertFalse(hold.expired());
        for (int i = 0; i < 100; i++) hold.tick();
        assertTrue(hold.expired());
        assertFalse(hold.shouldHold(player, null, "destination"));
    }
    @Test void completedMovementStillInvalidatesAnUnrelatedTeleport() {
        var hold = hold();
        assertFalse(hold.shouldHold(player, seat, "destination"));
        assertTrue(hold.completed());
        assertFalse(hold.shouldHold(player, null, "third dimension"));
        assertTrue(hold.invalidated());
        assertFalse(hold.shouldHold(player, null, "destination"));
    }
    @Test void timeoutReleasesAtTheExistingLimitAndCannotRearm() {
        var hold = hold();
        for (int i = 0; i < 100; i++) { assertTrue(hold.shouldHold(player, null, "source")); hold.tick(); }
        assertTrue(hold.expired()); assertFalse(hold.shouldHold(player, null, "source"));
        hold.tick(); assertFalse(hold.shouldHold(player, null, "destination"));
    }
    @Test void explicitCancellationReleasesImmediately() {
        var hold = hold(); hold.cancel();
        assertFalse(hold.shouldHold(player, null, "source"));
        assertFalse(hold.shouldHold(player, null, "destination"));
    }
}
