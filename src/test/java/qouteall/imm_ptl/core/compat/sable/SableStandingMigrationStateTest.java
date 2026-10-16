package qouteall.imm_ptl.core.compat.sable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableStandingMigrationStateTest {
    @Test
    void sameDimensionPortalDoesNotMistakeExistingStandingSupportForMigration() {
        assertFalse(SableStandingMigrationState.hasCompletedCrossDimensionTransfer(
            "overworld", new String("overworld"), "overworld", true
        ));
    }

    @Test
    void delayedCrossDimensionAcknowledgementStillRecognizesMigratedSupport() {
        assertTrue(SableStandingMigrationState.hasCompletedCrossDimensionTransfer(
            "overworld", "nether", new String("nether"), true
        ));
        assertTrue(SableStandingMigrationState.hasCompletedCrossDimensionTransfer(
            "nether", "overworld", "overworld", true
        ));
    }

    @Test
    void sourceOrUnrelatedDimensionCannotAcknowledgeMigration() {
        assertFalse(SableStandingMigrationState.hasCompletedCrossDimensionTransfer(
            "overworld", "nether", "overworld", true
        ));
        assertFalse(SableStandingMigrationState.hasCompletedCrossDimensionTransfer(
            "overworld", "nether", "end", true
        ));
    }

    @Test
    void destinationMustOwnTheExactSupportingBody() {
        assertFalse(SableStandingMigrationState.hasCompletedCrossDimensionTransfer(
            "overworld", "nether", "nether", false
        ));
    }
}
