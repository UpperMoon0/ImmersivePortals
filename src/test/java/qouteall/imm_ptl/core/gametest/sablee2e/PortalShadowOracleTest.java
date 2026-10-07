package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalShadowOracleTest {
    @Test void renderedProbeFollowsTheActualGlBit() {
        assertEquals(1, PortalShadowOracle.clipProbe(false));
        assertEquals(-1, PortalShadowOracle.clipProbe(true));
    }
    @Test void actualCasterAndReceiverPixelsAreBothRequired() {
        assertTrue(PortalShadowOracle.accepts(false, 1, 0, 0, 1, PortalShadowOracle.LIT_DEPTH, 7, true));
        assertTrue(PortalShadowOracle.accepts(true, 0, 1, 0, 1, PortalShadowOracle.CASTER_DEPTH, 7, true));
        assertFalse(PortalShadowOracle.accepts(true, 1, 0, 0, 1, 1, 7, true), "Empty shadow map must fail");
        assertFalse(PortalShadowOracle.accepts(true, 0, 0, 1, 1, PortalShadowOracle.CASTER_DEPTH, 3, true), "Leaked caster is not a receiver shadow");
        assertFalse(PortalShadowOracle.accepts(true, 0, 1, 0, 1, PortalShadowOracle.CASTER_DEPTH, 1000, true), "Sky/blank receiver must fail");
        assertFalse(PortalShadowOracle.accepts(true, 0, 1, 0, 0, PortalShadowOracle.CASTER_DEPTH, 7, true), "Whole-frame darkening is not a localized shadow");
        assertFalse(PortalShadowOracle.accepts(true, 0, 1, 0, 1, PortalShadowOracle.CASTER_DEPTH, 7, false), "Clipping state must restore");
    }
}
