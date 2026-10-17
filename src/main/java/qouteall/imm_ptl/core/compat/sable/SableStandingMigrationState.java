package qouteall.imm_ptl.core.compat.sable;

/** Dimension and ownership evidence for a delayed standing-passenger acknowledgement. */
final class SableStandingMigrationState {
    private SableStandingMigrationState() {}

    static boolean hasCompletedCrossDimensionTransfer(
        Object sourceDimension, Object destinationDimension, Object playerDimension,
        boolean destinationOwnsSupport
    ) {
        // For a same-dimension portal, owning the support in the destination is already true
        // before crossing. It cannot justify cancelling ordinary on-foot portal traversal.
        return !sourceDimension.equals(destinationDimension)
            && playerDimension.equals(destinationDimension)
            && destinationOwnsSupport;
    }
}
