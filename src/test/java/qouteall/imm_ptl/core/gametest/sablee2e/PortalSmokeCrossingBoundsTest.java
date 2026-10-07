package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalSmokeCrossingBoundsTest {
    @Test void acceptsObservedPortalExitButRejectsCoastingIntoWall() {
        assertTrue(PortalSmokeCrossingBounds.contains(0, 82.00000000476837, -0.09277962023878202));
        assertFalse(PortalSmokeCrossingBounds.contains(0, 82, -3.0));
        assertFalse(PortalSmokeCrossingBounds.contains(0, 82, -4.5));
        assertFalse(PortalSmokeCrossingBounds.contains(0, 82, 4));
    }

    @Test void rejectsLateralVerticalAndNonfiniteEndpointChanges() {
        assertFalse(PortalSmokeCrossingBounds.contains(2, 82, -0.1));
        assertFalse(PortalSmokeCrossingBounds.contains(0, 80, -0.1));
        assertFalse(PortalSmokeCrossingBounds.contains(0, Double.NaN, -0.1));
    }
}
