package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalSmokeCrumblingOracleTest {
    private static int gray(int value) { return value | value << 8 | value << 16; }
    private static int[] uniform(int size, int value) {
        int[] pixels = new int[size];
        Arrays.fill(pixels, gray(value));
        return pixels;
    }

    @Test void localizedCracksAreNotAveragedAwayByUnrelatedBackground() {
        int[] clean = uniform(400, 30), damaged = clean.clone(), backdrop = uniform(2000, 25);
        Arrays.fill(damaged, 0, 60, gray(18));
        // The former broad ROI would report only 60*12/(400+2000)=0.3 darkening.
        var result = PortalSmokeCrumblingOracle.compare(clean, damaged, backdrop, backdrop);
        assertEquals(1.8, result.meanDarkening(), 0.0001);
        assertTrue(result.showsDamage());
        assertFalse(result.restored());
        assertTrue(PortalSmokeCrumblingOracle.compare(clean, clean, backdrop, backdrop).restored());
    }

    @Test void BalancedMotionAndGlobalBrightnessChangesAreNotCracks() {
        int[] clean = uniform(400, 30), moved = clean.clone(), backdrop = uniform(400, 25);
        Arrays.fill(moved, 0, 100, gray(20));
        Arrays.fill(moved, 100, 200, gray(40));
        assertFalse(PortalSmokeCrumblingOracle.compare(clean, moved, backdrop, backdrop).showsDamage());
        assertFalse(PortalSmokeCrumblingOracle.compare(clean, uniform(400, 20), backdrop, uniform(400, 15)).showsDamage());
        assertFalse(PortalSmokeCrumblingOracle.compare(clean, clean, backdrop, backdrop).showsDamage());
    }

    @Test void MaskIsOnCogFaceAndExcludesSourceRotor() {
        assertTrue(PortalSmokeCrumblingOracle.facePixel(465, 202, 854, 480));
        assertFalse(PortalSmokeCrumblingOracle.facePixel(760, 202, 854, 480));
        assertTrue(PortalSmokeCrumblingOracle.backgroundPixel(565, 202, 854, 480));
        assertFalse(PortalSmokeCrumblingOracle.backgroundPixel(465, 202, 854, 480));
    }
}
