package qouteall.imm_ptl.core.compat.sable;

import java.util.UUID;

/** Bounded identity/coordinate-frame guard; contains no client or optional-mod classes. */
public final class SableRiderMovementHold {
    private final Object playerIdentity;
    private final UUID vehicleId;
    private final Object sourceDimension;
    private final Object destinationDimension;
    private final int maximumTicks;
    private int ticks;
    private boolean cancelled;
    private boolean detached;
    private boolean invalidated;
    private boolean completed;

    public SableRiderMovementHold(Object playerIdentity, UUID vehicleId, Object sourceDimension,
        Object destinationDimension, int maximumTicks) {
        if (maximumTicks <= 0) throw new IllegalArgumentException("Positive movement hold lifetime required");
        this.playerIdentity = playerIdentity; this.vehicleId = vehicleId;
        this.sourceDimension = sourceDimension; this.destinationDimension = destinationDimension;
        this.maximumTicks = maximumTicks;
    }
    /** Observe the actual local player, even while mounted, so a completed hold cannot rearm. */
    public boolean shouldHold(Object currentPlayer, UUID currentVehicle, Object dimension) {
        if (expired() || vehicleId == null) return false;
        if (playerIdentity != currentPlayer
            || (!sourceDimension.equals(dimension) && !destinationDimension.equals(dimension))
            || (currentVehicle != null && !vehicleId.equals(currentVehicle))) {
            invalidated = true;
            cancel();
            return false;
        }
        // Movement completion is not protocol completion: keep observing lifecycle and time
        // until the terminal Ack/cancel, without ever holding a subsequent dismount.
        if (completed) return false;
        if (currentVehicle != null) {
            if (detached || destinationDimension.equals(dimension)) {
                completed = true;
            }
            return false;
        }
        detached = true;
        return true;
    }
    public boolean invalidated() { return invalidated; }
    public boolean completed() { return completed; }
    public void tick() { if (ticks < maximumTicks) ticks++; }
    public boolean expired() { return cancelled || ticks >= maximumTicks; }
    public void cancel() { cancelled = true; }
}
