package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.UUID;

/** Development-only assertion policy for the ordered detach/teleport/remount packet window. */
final class SableRiderHandoffGrace {
    private SableRiderHandoffGrace() {}

    /** Returns true only for a bounded, explicitly active migration with a staged destination. */
    static boolean verifySourceSeat(
        UUID expectedVehicle, UUID actualVehicle, boolean correlatedHandoff,
        boolean destinationStaged, int detachedTicks, int maximumDetachedTicks
    ) {
        if (expectedVehicle != null && expectedVehicle.equals(actualVehicle)) return false;
        if (actualVehicle == null && expectedVehicle != null && correlatedHandoff && destinationStaged
            && detachedTicks > 0 && detachedTicks <= maximumDetachedTicks) return true;
        throw new IllegalStateException("client lost Create seat outside the allowed correlated handoff window"
            + " expectedVehicle=" + expectedVehicle + " actualVehicle=" + actualVehicle
            + " correlatedHandoff=" + correlatedHandoff + " destinationStaged=" + destinationStaged
            + " detachedTicks=" + detachedTicks + "/" + maximumDetachedTicks);
    }

    static boolean matchesHandoff(
        UUID expectedVehicle, UUID capturedVehicle, Object expectedSource, Object capturedSource,
        Object expectedDestination, Object capturedDestination
    ) {
        return expectedVehicle != null && expectedVehicle.equals(capturedVehicle)
            && expectedSource != null && expectedSource.equals(capturedSource)
            && expectedDestination != null && expectedDestination.equals(capturedDestination);
    }

    /**
     * Destination full-sync precedes entity transfer; source retirement is queued only when
     * that transaction commits, before its terminal Ack. Only the correlated, bounded detach
     * window before that Ack may pause the stale-copy deadline. Keep any earlier stale ticks:
     * entering a handoff must not reset a duplicate that was already present.
     */
    static int verifyOverlap(
        boolean copiesOverlap, boolean correlatedDetachedWindow, String handoffPhase,
        int detachedTicks, int maximumDetachedTicks, int previousStaleTicks, int maximumStaleTicks
    ) {
        if (!copiesOverlap) return 0;
        boolean awaitingCommit = correlatedDetachedWindow
            && "awaiting-authoritative-ack".equals(handoffPhase)
            && detachedTicks > 0 && detachedTicks <= maximumDetachedTicks;
        int staleTicks = previousStaleTicks + (awaitingCommit ? 0 : 1);
        if (staleTicks > maximumStaleTicks) {
            throw new IllegalStateException("source and destination Sable client copies overlapped too long"
                + " staleOverlapTicks=" + staleTicks + "/" + maximumStaleTicks
                + " correlatedDetachedWindow=" + correlatedDetachedWindow
                + " handoffPhase=" + handoffPhase
                + " detachedTicks=" + detachedTicks + "/" + maximumDetachedTicks);
        }
        return staleTicks;
    }


    static void verifySpatialDistance(
        double currentOrSourceDistance, double stagedDestinationDistance, boolean correlatedWindow, double maximumDistance
    ) {
        double distance = correlatedWindow
            ? Math.min(currentOrSourceDistance, stagedDestinationDistance) : currentOrSourceDistance;
        if (!Double.isFinite(distance) || distance > maximumDistance) {
            throw new IllegalStateException("Sable body/rider spatial continuity failed"
                + " sourceOrCurrentDistance=" + currentOrSourceDistance
                + " stagedDestinationDistance=" + stagedDestinationDistance
                + " correlatedWindow=" + correlatedWindow + " maximumDistance=" + maximumDistance);
        }
    }

}
