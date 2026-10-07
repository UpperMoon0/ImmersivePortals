package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.UUID;

/** Development-only assertion policy for the ordered detach/teleport/remount packet window. */
final class SableRiderHandoffGrace {
    private SableRiderHandoffGrace() {}

    /** Returns true only for a bounded, explicitly active migration with a staged destination. */
    static boolean verifySourceSeat(
        UUID expectedVehicle, UUID actualVehicle, boolean activeServerHandoff,
        boolean destinationStaged, int detachedTicks, int maximumDetachedTicks
    ) {
        if (expectedVehicle != null && expectedVehicle.equals(actualVehicle)) return false;
        if (actualVehicle == null && expectedVehicle != null && activeServerHandoff && destinationStaged
            && detachedTicks > 0 && detachedTicks <= maximumDetachedTicks) return true;
        throw new IllegalStateException("client lost Create seat outside the allowed server-first handoff window"
            + " expectedVehicle=" + expectedVehicle + " actualVehicle=" + actualVehicle
            + " activeServerHandoff=" + activeServerHandoff + " destinationStaged=" + destinationStaged
            + " detachedTicks=" + detachedTicks + "/" + maximumDetachedTicks);
    }
}
