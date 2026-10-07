package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static qouteall.imm_ptl.core.compat.iris_compatibility.IrisClippingPolicy.Action.*;

class IrisClippingPolicyTest {
    @Test
    void shadersOffPreservesVanillaAndPortalAreaClippingRegardlessOfIrisInstallation() {
        for (boolean enabled : new boolean[]{false, true}) {
            for (boolean hasVanillaUniform : new boolean[]{false, true}) {
                assertEquals(PRESERVE, IrisClippingPolicy.forDraw(
                    false, false, true, enabled, false, hasVanillaUniform));
            }
        }
    }

    @Test
    void portalAreaAndOtherVanillaClippingUniformsRemainActiveWithShaderpacks() {
        assertEquals(PRESERVE, IrisClippingPolicy.forDraw(true, false, true, true, false, true));
        // Do not replace their before-model-view equation with the Iris equation.
        assertEquals(PRESERVE, IrisClippingPolicy.forDraw(true, false, true, false, false, true));
    }

    @Test
    void onlyTransformedIrisWorldDrawsCreateMissingPortalScope() {
        assertEquals(ENABLE_WORLD_CLIPPING, IrisClippingPolicy.forDraw(true, false, true, false, true, false));
        assertEquals(PRESERVE, IrisClippingPolicy.forDraw(true, false, false, false, true, false));
        assertEquals(PRESERVE, IrisClippingPolicy.forDraw(true, false, true, true, true, false));
        assertEquals(PRESERVE, IrisClippingPolicy.forDraw(true, false, true, false, false, false));
    }

    @Test
    void activePackUnpatchedAndShadowDrawsSuspendInheritedClipping() {
        assertEquals(SUSPEND, IrisClippingPolicy.forDraw(true, false, true, true, false, false));
        assertEquals(SUSPEND, IrisClippingPolicy.forDraw(true, true, true, true, true, true));
        assertEquals(PRESERVE, IrisClippingPolicy.forDraw(true, true, true, false, false, false));
    }
}
