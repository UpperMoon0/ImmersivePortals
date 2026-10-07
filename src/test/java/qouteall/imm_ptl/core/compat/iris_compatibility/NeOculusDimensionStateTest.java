package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NeOculusDimensionStateTest {
    @Test
    void coldStartBeforeTitleScreenHasTheSameDefaultAsLoadingComplete() {
        Object overworld = new Object();
        Object remembered = NeOculusDimensionState.initializeIfAbsent(null, overworld);
        // No-world disconnect compares currentDimension (the remembered cache) to lastDimension.
        assertSame(overworld, remembered);
        assertEquals(remembered, remembered);
        assertSame(remembered, NeOculusDimensionState.initializeIfAbsent(remembered, overworld));
    }

    @Test
    void rememberedDimensionSurvivesDisconnectReconnectAndResourceReload() {
        Object overworld = new Object();
        Object nether = new Object();
        Object customDimension = new Object();
        // Only an absent cache is initialized. Existing world-derived values are never replaced.
        for (Object current : new Object[]{overworld, nether, customDimension}) {
            assertSame(current, NeOculusDimensionState.initializeIfAbsent(current, overworld));
            assertSame(current, NeOculusDimensionState.initializeIfAbsent(current, null));
        }
    }
}
