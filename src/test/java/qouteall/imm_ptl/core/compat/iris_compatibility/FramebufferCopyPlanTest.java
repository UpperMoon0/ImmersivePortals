package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FramebufferCopyPlanTest {
    @Test
    void unavailableEntryPointAndPartialDepthStencilCopiesAlwaysUseBlit() {
        assertFalse(FramebufferCopyPlan.useCopyImage(false, true));
        assertFalse(FramebufferCopyPlan.useCopyImage(false, false));
        assertFalse(FramebufferCopyPlan.useCopyImage(true, false));
        assertTrue(FramebufferCopyPlan.useCopyImage(true, true));
    }

    @Test
    void copiesRequireExactStorageNotVendorDependentConversions() {
        assertDoesNotThrow(() -> FramebufferCopyPlan.requireMatchingStorage(0x81A7, 0x81A7, 800, 600, 800, 600));
        assertThrows(IllegalStateException.class,
            () -> FramebufferCopyPlan.requireMatchingStorage(0x81A7, 0x88F0, 800, 600, 800, 600));
        assertThrows(IllegalStateException.class,
            () -> FramebufferCopyPlan.requireMatchingStorage(0x8058, 0x8058, 800, 600, 1600, 1200));
        assertThrows(IllegalStateException.class,
            () -> FramebufferCopyPlan.requireMatchingStorage(0x8058, 0x8058, 0, 0, 0, 0));
    }
}
