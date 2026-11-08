package qouteall.imm_ptl.core.compat.valkyrienskies;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-thread-only transaction ledger for an externally owned physics ship.
 *
 * <p>Never "rescue" an unsuccessful native transfer by copying its blocks:
 * two physics worlds owning the same claim is worse than a rejected crossing.
 * An adapter must prove native rollback before production may enable transfers.
 */
public final class ShipTransitCoordinator<S> {
    public enum Outcome {
        COMMITTED, REJECTED, PREPARATION_FAILED, NATIVE_MOVE_FAILED,
        CONFIRMATION_FAILED_ROLLED_BACK
    }

    public interface NativeTransfer<S> {
        /** Validate the destination, claim, network readiness and all dependent passengers. */
        boolean prepare(S ship);
        /** A native, all-or-nothing transfer. Must not create a second ship. */
        boolean move(S ship);
        /** Check unique destination ownership, dimensions and dependent state. */
        boolean confirm(S ship);
        /** Restore the same native ship and its dependents, never a copy. */
        boolean rollback(S ship);
    }

    private final Map<S, Long> nextEligibleTick = new IdentityHashMap<>();
    private final long cooldownTicks;

    public ShipTransitCoordinator(long cooldownTicks) {
        if (cooldownTicks < 1 || cooldownTicks > 200) {
            throw new IllegalArgumentException("cooldownTicks must be 1..200");
        }
        this.cooldownTicks = cooldownTicks;
    }

    /**
     * Runs all phases synchronously on the server thread.
     * An exception after a native move is not silently swallowed.
     */
    public synchronized Outcome transfer(S ship, long serverTick, NativeTransfer<S> nativeTransfer) {
        Objects.requireNonNull(ship, "ship");
        Objects.requireNonNull(nativeTransfer, "nativeTransfer");
        if (serverTick < 0) throw new IllegalArgumentException("negative tick");
        Long eligible = nextEligibleTick.get(ship);
        if (eligible != null && serverTick < eligible) return Outcome.REJECTED;
        // Reserve before calling third-party code: recursive/duplicate triggers
        // cannot initiate another move during the same transaction.
        nextEligibleTick.put(ship, Math.addExact(serverTick, cooldownTicks));
        boolean prepared;
        try {
            prepared = nativeTransfer.prepare(ship);
        } catch (RuntimeException ex) {
            nextEligibleTick.remove(ship);
            throw ex;
        }
        if (!prepared) {
            nextEligibleTick.remove(ship);
            return Outcome.PREPARATION_FAILED;
        }

        boolean moved;
        try {
            moved = nativeTransfer.move(ship);
        } catch (RuntimeException ex) {
            // Native API may have partially mutated ownership. Only the adapter
            // can safely recover; do not run an unproven heuristic rollback.
            throw new IllegalStateException("Uncertain native ship move; server must halt this transfer", ex);
        }
        if (!moved) return Outcome.NATIVE_MOVE_FAILED;
        if (nativeTransfer.confirm(ship)) return Outcome.COMMITTED;
        if (!nativeTransfer.rollback(ship)) {
            throw new IllegalStateException("Native ship transfer failed confirmation and rollback; ownership uncertain");
        }
        return Outcome.CONFIRMATION_FAILED_ROLLED_BACK;
    }

    public synchronized void expire(long serverTick) {
        nextEligibleTick.entrySet().removeIf(e -> e.getValue() <= serverTick);
    }

    public synchronized void reset() {
        nextEligibleTick.clear();
    }

    public synchronized int retainedShipCount() {
        return nextEligibleTick.size();
    }
}
