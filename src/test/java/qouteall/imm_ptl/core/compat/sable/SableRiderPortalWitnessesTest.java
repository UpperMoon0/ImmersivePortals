package qouteall.imm_ptl.core.compat.sable;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SableRiderPortalWitnessesTest {
    private final UUID player = UUID.randomUUID(), body = UUID.randomUUID();
    private final UUID vehicle = UUID.randomUUID(), portal = UUID.randomUUID();
    private final Object source = new Object(), destination = new Object();
    private SableRiderPortalWitnesses.Key key() {
        return new SableRiderPortalWitnesses.Key(player, body, vehicle, portal, source, destination);
    }

    @Test void witnessLivesOnlyForItsBoundedForwardTimeWindow() {
        var store = new SableRiderPortalWitnesses(100); store.record(key(), 200);
        assertFalse(store.has(key(), 199)); assertTrue(store.has(key(), 200));
        assertTrue(store.has(key(), 300)); assertFalse(store.has(key(), 301));
        store.expire(source, 301); assertTrue(store.keys().isEmpty());
    }

    @Test void everyIdentityComponentScopesTheWitness() {
        var store = new SableRiderPortalWitnesses(100); store.record(key(), 200);
        assertFalse(store.has(new SableRiderPortalWitnesses.Key(UUID.randomUUID(), body, vehicle, portal, source, destination), 201));
        assertFalse(store.has(new SableRiderPortalWitnesses.Key(player, UUID.randomUUID(), vehicle, portal, source, destination), 201));
        assertFalse(store.has(new SableRiderPortalWitnesses.Key(player, body, UUID.randomUUID(), portal, source, destination), 201));
        assertFalse(store.has(new SableRiderPortalWitnesses.Key(player, body, vehicle, UUID.randomUUID(), source, destination), 201));
        assertFalse(store.has(new SableRiderPortalWitnesses.Key(player, body, vehicle, portal, new Object(), destination), 201));
        assertFalse(store.has(new SableRiderPortalWitnesses.Key(player, body, vehicle, portal, source, new Object()), 201));
    }

    @Test void dimensionClockCleanupDoesNotExpireAnotherWorld() {
        var store = new SableRiderPortalWitnesses(5); store.record(key(), 100);
        store.expire(destination, 1000); assertTrue(store.has(key(), 101));
        store.expire(source, 99); assertTrue(store.keys().isEmpty());
    }

    @Test void newMountCannotInheritThePreviousAnimationInterval() {
        var store = new SableRiderPortalWitnesses(100);
        store.observeMount(player, body, vehicle, source, 100);
        assertFalse(store.hasStableMount(key(), 100)); assertTrue(store.hasStableMount(key(), 101));
        store.record(key(), 101);
        store.invalidatePlayer(player); // Dismount, including dismount/remount in the same tick.
        store.observeMount(player, body, vehicle, source, 101);
        assertFalse(store.has(key(), 101)); assertFalse(store.hasStableMount(key(), 101));
        assertTrue(store.hasStableMount(key(), 102));
    }

    @Test void changingBodyVehicleOrWorldInvalidatesTheMountGeneration() {
        var store = new SableRiderPortalWitnesses(100);
        store.observeMount(player, body, vehicle, source, 100); store.record(key(), 101);
        store.observeMount(player, body, UUID.randomUUID(), source, 102);
        assertFalse(store.has(key(), 102)); assertFalse(store.hasStableMount(key(), 103));
        store.observeMount(player, body, vehicle, source, 104); store.record(key(), 105);
        store.observeMount(player, body, vehicle, destination, 106);
        assertFalse(store.has(key(), 106)); assertFalse(store.hasStableMount(key(), 107));
        store.observeMount(player, body, vehicle, source, 108); store.record(key(), 109);
        store.observeMount(player, UUID.randomUUID(), vehicle, source, 110);
        assertFalse(store.has(key(), 110));
    }

    @Test void committedMigrationInvalidatesAllRidersAndPreventsSameTickRecapture() {
        var store = new SableRiderPortalWitnesses(100);
        var other = new SableRiderPortalWitnesses.Key(UUID.randomUUID(), body, UUID.randomUUID(), portal, source, destination);
        store.observeMount(player, body, vehicle, source, 100);
        store.record(key(), 101); store.record(other, 101);
        store.invalidateBody(body);
        assertFalse(store.has(key(), 102)); assertFalse(store.has(other, 102));
        store.observeMount(player, body, vehicle, source, 102);
        assertFalse(store.hasStableMount(key(), 102));
    }

    @Test void returnToFrontInvalidationAndServerCleanupRemoveAuthorization() {
        var store = new SableRiderPortalWitnesses(100);
        store.observeMount(player, body, vehicle, source, 100); store.record(key(), 101);
        store.invalidate(key()); assertFalse(store.has(key(), 102));
        store.record(key(), 103); store.clear();
        assertFalse(store.has(key(), 104)); assertFalse(store.hasStableMount(key(), 104));
    }

    @Test void exactPlaneContactsShareDismountMigrationAndCleanupInvalidation() {
        for (int invalidation = 0; invalidation < 4; invalidation++) {
            var store = new SableRiderPortalWitnesses(100);
            Object before = new Object(), zero = new Object(), after = new Object();
            assertFalse(store.samplePlaneContact(key(), before, zero, 100, 1, 0, true));
            assertTrue(store.hasPlaneContact(key(), 100));
            switch (invalidation) {
                case 0 -> store.invalidatePlayer(player);
                case 1 -> store.invalidateBody(body);
                case 2 -> store.invalidate(key());
                case 3 -> store.clear();
            }
            assertFalse(store.hasPlaneContact(key(), 101));
            assertFalse(store.samplePlaneContact(key(), zero, after, 101, 0, -1, true));
        }
    }

    @Test void mountGenerationChangeCannotReuseAnArmedPlaneContact() {
        var store = new SableRiderPortalWitnesses(100);
        Object before = new Object(), zero = new Object(), after = new Object();
        store.observeMount(player, body, vehicle, source, 99);
        store.samplePlaneContact(key(), before, zero, 100, 1, 0, true);
        store.observeMount(player, body, UUID.randomUUID(), source, 101);
        store.observeMount(player, body, vehicle, source, 102);
        assertFalse(store.hasPlaneContact(key(), 102));
        assertFalse(store.samplePlaneContact(key(), zero, after, 103, 0, -1, true));
    }

    @Test void planeContactsAreDimensionScopedExpireAndCannotCrossTargetIdentity() {
        var store = new SableRiderPortalWitnesses(5);
        Object before = new Object(), zero = new Object(), after = new Object();
        store.samplePlaneContact(key(), before, zero, 100, 1, 0, true);
        store.expire(destination, 1000);
        assertTrue(store.hasPlaneContact(key(), 101));
        var editedDestination = new SableRiderPortalWitnesses.Key(player, body, vehicle, portal, source, new Object());
        assertFalse(store.samplePlaneContact(editedDestination, zero, after, 101, 0, -1, true));
        store.expire(source, 106);
        assertFalse(store.hasPlaneContact(key(), 106));
        assertFalse(store.samplePlaneContact(key(), zero, after, 107, 0, -1, true));
    }

    @Test void recordingCrossingConsumesTheContactAndDoesNotAuthorizeAReplay() {
        var store = new SableRiderPortalWitnesses(100);
        Object before = new Object(), zero = new Object(), after = new Object();
        store.samplePlaneContact(key(), before, zero, 100, 1, 0, true);
        assertTrue(store.samplePlaneContact(key(), zero, after, 101, 0, -1, true));
        store.record(key(), 101);
        assertTrue(store.has(key(), 101)); assertFalse(store.hasPlaneContact(key(), 101));
        store.invalidateBody(body);
        assertFalse(store.samplePlaneContact(key(), zero, after, 102, 0, -1, true));
        assertFalse(store.has(key(), 102));
    }
}
