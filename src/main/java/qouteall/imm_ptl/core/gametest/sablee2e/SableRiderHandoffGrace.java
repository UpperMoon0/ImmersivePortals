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
