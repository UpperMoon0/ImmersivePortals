package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IrisDebugLabelPolicyTest {
    @Test
    void suppressesOnlyTheKnownAbsentDepthTextureLabel() {
        assertFalse(IrisDebugLabelPolicy.shouldLabel(0x1702, -1, "Main depth texture"));
        assertTrue(IrisDebugLabelPolicy.shouldLabel(0x1702, 1, "Main depth texture"));
        assertTrue(IrisDebugLabelPolicy.shouldLabel(0x1702, 0, "Main depth texture"));
        assertTrue(IrisDebugLabelPolicy.shouldLabel(0x1702, -2, "Main depth texture"));
        assertTrue(IrisDebugLabelPolicy.shouldLabel(0x1702, -1, "Main color texture"));
        assertTrue(IrisDebugLabelPolicy.shouldLabel(0x82E0, -1, "Main depth texture"));
        assertTrue(IrisDebugLabelPolicy.shouldLabel(0x1702, -1, null));
    }
}
