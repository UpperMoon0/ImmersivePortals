package qouteall.imm_ptl.core.gametest.sablee2e;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SableShipPanelPixelsTest {
    @Test void requiresRequestedPanelAndBothDistinctiveFlankingColors() {
        assertTrue(SableShipPanelPixels.accepts(40_000,100,20_000,20_000,100_000));
        assertFalse(SableShipPanelPixels.accepts(1_200,40_000,20_000,20_000,100_000));
        assertFalse(SableShipPanelPixels.accepts(25_000,40_000,10_000,10_000,100_000));
        assertFalse(SableShipPanelPixels.accepts(1_200,0,20_000,20_000,100_000));
        assertFalse(SableShipPanelPixels.accepts(90_000,0,0,0,100_000), "Bare red Nether background must fail");
        assertFalse(SableShipPanelPixels.accepts(40_000,0,20_000,0,100_000));
    }
    @Test void rejectsBlankMalformedAndOutOfRangeReceipts() {
        assertFalse(SableShipPanelPixels.accepts(0,0,0,0,100_000));
        assertFalse(SableShipPanelPixels.accepts(1_000,0,500,500,0));
        assertFalse(SableShipPanelPixels.accepts(101_000,0,20_000,20_000,100_000));
        assertFalse(SableShipPanelPixels.accepts(40_000,-1,20_000,20_000,100_000));
        assertFalse(SableShipPanelPixels.accepts(20_000,0,20_000,20_000,100_001));
    }
}
