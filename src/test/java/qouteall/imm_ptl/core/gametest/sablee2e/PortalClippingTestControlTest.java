package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalClippingTestControlTest {
    @Test
    void entityNegativeDoesNotDisableTerrainParticlesOrBlockEntities() {
        PortalClippingTestControl.install("entity-clipping-disabled");
        assertFalse(PortalClippingTestControl.select("VANILLA", "entities_cutout_diffuse", true));
        assertTrue(PortalClippingTestControl.select("VANILLA", "particles", true));
        assertTrue(PortalClippingTestControl.select("VANILLA", "block_entity", true));
        assertTrue(PortalClippingTestControl.select("SODIUM", "gbuffers_terrain_solid", true));
        assertFalse(PortalClippingTestControl.select("VANILLA", "shadow_entities", false));
    }

    @Test
    void negativeNeedsActualTargetPortalDrawWithMissingUniform() {
        PortalClippingTestControl.install("particle-clipping-disabled");
        assertFalse(PortalClippingTestControl.select("VANILLA", "particles", true));
        PortalClippingTestControl.recordSource("particles", "gbuffers_particles");
        PortalClippingTestControl.recordDraw("particles", false, false);
        assertThrows(IllegalStateException.class, () -> PortalClippingTestControl.assertTargetDrawn("particle", false));
        PortalClippingTestControl.recordDraw("particles", true, true);
        assertThrows(IllegalStateException.class, () -> PortalClippingTestControl.assertTargetDrawn("particle", false));
        PortalClippingTestControl.recordDraw("particles", false, true);
        assertDoesNotThrow(() -> PortalClippingTestControl.assertTargetDrawn("particle", false));
        PortalClippingTestControl.beginObservation("next scene");
        assertThrows(IllegalStateException.class, () -> PortalClippingTestControl.assertTargetDrawn("particle", false));
    }

    @Test
    void positiveNeedsSelectedSourceAndCompletedPortalDrawWithUniform() {
        PortalClippingTestControl.install("");
        PortalClippingTestControl.recordDraw("entities_cutout_diffuse", true, true);
        assertThrows(IllegalStateException.class, () -> PortalClippingTestControl.assertTargetDrawn("entity", true));
        assertTrue(PortalClippingTestControl.select("VANILLA", "entities_cutout_diffuse", true));
        PortalClippingTestControl.recordSource("entities_cutout_diffuse", "gbuffers_entities");
        assertDoesNotThrow(() -> PortalClippingTestControl.assertTargetDrawn("entity", true));
    }
}
