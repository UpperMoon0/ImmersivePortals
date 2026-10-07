package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalClippingTestControlTest {
    @Test
    void depthPixelCountIsIndependentFromCapturedFrameCount() {
        PortalClippingTestControl.install("");
        PortalClippingTestControl.beginObservation("before-reload:solid-background");
        for (int frame = 0; frame < 130; frame++) {
            PortalClippingTestControl.recordInnerDepth("minecraft:the_nether:1",
                java.util.Map.of("sampleCount", 81, "depthSamples", new float[81]));
        }
        var states = (java.util.Map<?, ?>) PortalClippingTestControl.evidence().get("innerWorldDepthStates");
        var depth = (java.util.Map<?, ?>) states.get("minecraft:the_nether:1");
        assertEquals(81, depth.get("sampleCount"));
        assertEquals(130, depth.get("observationCount"));
        assertEquals(81, ((float[]) depth.get("depthSamples")).length);
        PortalClippingTestControl.beginObservation("after-reload:solid-background");
        assertTrue(((java.util.Map<?, ?>) PortalClippingTestControl.evidence().get("innerWorldDepthStates")).isEmpty());
    }

    @Test
    void nativeDepthIsObservedOnlyForTheCrossingEndpoint() {
        PortalClippingTestControl.install("");
        assertFalse(PortalClippingTestControl.observesNativeDepth());
        PortalClippingTestControl.beginObservation("before-reload:solid-visible");
        assertFalse(PortalClippingTestControl.observesNativeDepth());
        PortalClippingTestControl.beginObservation("after-crossing:crossing");
        assertTrue(PortalClippingTestControl.observesNativeDepth());
    }

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
