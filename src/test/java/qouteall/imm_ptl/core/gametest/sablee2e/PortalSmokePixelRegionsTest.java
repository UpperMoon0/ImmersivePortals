package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalSmokePixelRegionsTest {
    @Test void retainedPositivePanelUsesItsProjectedInterior() {
        double[] region = PortalSmokePixelRegions.controlRegion("entity-visible");
        assertTrue(region[0] > 434.0 / 854 && region[2] < 679.0 / 854);
        assertArrayEquals(new double[]{0.60, 0.45, 0.66, 0.55}, region);
    }

    @Test void calibrationCannotMoveEitherExcludedSideOracle() {
        double[] excluded = {0.39, 0.45, 0.45, 0.55};
        assertArrayEquals(excluded, PortalSmokePixelRegions.controlRegion("entity-clipped"));
        assertArrayEquals(excluded, PortalSmokePixelRegions.controlRegion("particle-clipped"));
        assertArrayEquals(new double[]{0.56, 0.45, 0.62, 0.55}, PortalSmokePixelRegions.retainedRegion());
    }
    @Test void coloredNetherBackgroundStillDistinguishesOccludingRedGeometry() {
        int ochre = 37 | (17 << 8) | (4 << 16);
        int red = 51 | (5 << 8) | (4 << 16);
        var same = PortalSmokePixelRegions.difference(new int[]{ochre}, new int[]{ochre});
        var blocked = PortalSmokePixelRegions.difference(new int[]{ochre}, new int[]{red});
        assertTrue(same.matchesBackground());
        assertFalse(same.visiblyDifferent());
        assertFalse(blocked.matchesBackground());
        assertTrue(blocked.visiblyDifferent());
    }

    @Test void nativeCrossingRequiresBackdropPaletteInsteadOfRedOrSky() {
        double[] backdrop = {37, 17, 4}, red = {51, 5, 4};
        assertTrue(PortalSmokePixelRegions.matchesCrossingPalette(backdrop, red, backdrop));
        assertTrue(PortalSmokePixelRegions.matchesCrossingPalette(backdrop, red, new double[]{60, 160, 20}));
        assertFalse(PortalSmokePixelRegions.matchesCrossingPalette(backdrop, red, red));
        assertFalse(PortalSmokePixelRegions.matchesCrossingPalette(backdrop, red, new double[]{100, 150, 240}));
        assertFalse(PortalSmokePixelRegions.matchesCrossingPalette(backdrop, red, new double[]{0, 0, 0}));
    }
    @Test void darkReferenceCannotMakeABlackMissingDrawPass() {
        int dark = 3 | (3 << 8) | (3 << 16);
        assertFalse(PortalSmokePixelRegions.difference(new int[]{dark}, new int[]{0}).matchesBackground());
    }

    @Test void depthContextsAreDistinctAndMirrorColorControlStaysOnSameSurface() {
        assertEquals(new PortalSmokePixelRegions.DepthTarget("minecraft:the_end:2", 8), PortalSmokePixelRegions.depthTarget("nested-background"));
        assertEquals(new PortalSmokePixelRegions.DepthTarget("minecraft:overworld:1", 10), PortalSmokePixelRegions.depthTarget("mirror-background"));
        assertEquals("same", PortalSmokePixelRegions.depthExpectation("mirror-visible"));
        assertEquals("nearer", PortalSmokePixelRegions.depthExpectation("nested-visible"));
    }
}
