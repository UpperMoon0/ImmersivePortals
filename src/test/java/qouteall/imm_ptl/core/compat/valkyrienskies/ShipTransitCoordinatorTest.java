package qouteall.imm_ptl.core.compat.valkyrienskies;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static qouteall.imm_ptl.core.compat.valkyrienskies.ShipTransitCoordinator.Outcome.*;

class ShipTransitCoordinatorTest {
    private static class Native implements ShipTransitCoordinator.NativeTransfer<Object> {
        final List<String> calls = new ArrayList<>();
        boolean prepared = true, moved = true, confirmed = true, rolledBack = true;
        Runnable onMove = () -> {};
        @Override public boolean prepare(Object s) { calls.add("prepare"); return prepared; }
        @Override public boolean move(Object s) { calls.add("move"); onMove.run(); return moved; }
        @Override public boolean confirm(Object s) { calls.add("confirm"); return confirmed; }
        @Override public boolean rollback(Object s) { calls.add("rollback"); return rolledBack; }
    }

    @Test void commitsOnceAndBlocksDuplicateUntilCooldown() {
        var coordinator = new ShipTransitCoordinator<Object>(20);
        Object ship = new Object();
        Native nativeApi = new Native();
        assertEquals(COMMITTED, coordinator.transfer(ship, 100, nativeApi));
        assertEquals(REJECTED, coordinator.transfer(ship, 100, nativeApi));
        assertEquals(REJECTED, coordinator.transfer(ship, 119, nativeApi));
        assertEquals(COMMITTED, coordinator.transfer(ship, 120, nativeApi));
        assertEquals(List.of("prepare", "move", "confirm", "prepare", "move", "confirm"), nativeApi.calls);
    }

    @Test void reentrantNativeApiCannotDuplicateShip() {
        var coordinator = new ShipTransitCoordinator<Object>(20);
        var ship = new Object();
        Native nativeApi = new Native();
        AtomicBoolean observedRejection = new AtomicBoolean();
        nativeApi.onMove = () -> observedRejection.set(
            coordinator.transfer(ship, 100, new Native()) == REJECTED);
        assertEquals(COMMITTED, coordinator.transfer(ship, 100, nativeApi));
        assertTrue(observedRejection.get());
    }

    @Test void failedPreparationAllowsRetryWithoutNativeMutation() {
        var coordinator = new ShipTransitCoordinator<Object>(20);
        var ship = new Object();
        Native nativeApi = new Native();
        nativeApi.prepared = false;
        assertEquals(PREPARATION_FAILED, coordinator.transfer(ship, 100, nativeApi));
        assertEquals(List.of("prepare"), nativeApi.calls);
        nativeApi.prepared = true;
        assertEquals(COMMITTED, coordinator.transfer(ship, 100, nativeApi));
    }

    @Test void failedMoveDoesNotPretendToCommit() {
        var coordinator = new ShipTransitCoordinator<Object>(20);
        Native nativeApi = new Native();
        nativeApi.moved = false;
        assertEquals(NATIVE_MOVE_FAILED, coordinator.transfer(new Object(), 30, nativeApi));
        assertEquals(List.of("prepare", "move"), nativeApi.calls);
    }

    @Test void failedPostconditionForcesNativeRollback() {
        var coordinator = new ShipTransitCoordinator<Object>(20);
        Native nativeApi = new Native();
        nativeApi.confirmed = false;
        assertEquals(CONFIRMATION_FAILED_ROLLED_BACK,
            coordinator.transfer(new Object(), 30, nativeApi));
        assertEquals(List.of("prepare", "move", "confirm", "rollback"), nativeApi.calls);
    }

    @Test void failedRollbackIsFatalRatherThanCreatingASecondShip() {
        var coordinator = new ShipTransitCoordinator<Object>(20);
        Native nativeApi = new Native();
        nativeApi.confirmed = false;
        nativeApi.rolledBack = false;
        assertThrows(IllegalStateException.class,
            () -> coordinator.transfer(new Object(), 30, nativeApi));
    }

    @Test void expiredEntriesAndExplicitResetReleaseReferences() {
        var coordinator = new ShipTransitCoordinator<Object>(20);
        coordinator.transfer(new Object(), 10, new Native());
        assertEquals(1, coordinator.retainedShipCount());
        coordinator.expire(29);
        assertEquals(1, coordinator.retainedShipCount());
        coordinator.expire(30);
        assertEquals(0, coordinator.retainedShipCount());
        coordinator.transfer(new Object(), 30, new Native());
        coordinator.reset();
        assertEquals(0, coordinator.retainedShipCount());
    }
}
