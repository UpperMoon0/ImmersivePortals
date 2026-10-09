package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortalClippingTestControlTest {
    @Test
    void depthPixelCountIsIndependentFromCapturedFrameCount() {
        PortalClippingTestControl.install("");
        PortalClippingTestControl.beginObservation("before-reload:solid-background");
        for (int frame = 1; frame <= 130; frame++) {
            PortalClippingTestControl.beginFrame(frame, true);
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
    void depthReadbackIsArmedOnlyOnUnchangedAssertionCadence() {
        PortalClippingTestControl.install("");
        PortalClippingTestControl.beginObservation("before-reload:nested-background");
        int captures = 0;
        for (int frame = 1; frame <= 140; frame++) {
            PortalClippingTestControl.beginFrame(frame, PortalClippingTestControl.assertionFrame(frame));
            if (PortalClippingTestControl.observesInnerDepth()) {
                captures++;
                PortalClippingTestControl.recordInnerDepth("minecraft:the_end:2",
                    java.util.Map.of("sampleCount", 81, "depthSamples", new float[81], "readbackNanos", 5L));
                var state = PortalClippingTestControl.requireCurrentDepth("minecraft:the_end:2");
                assertEquals((long) frame, state.get("frame"));
                assertEquals("before-reload:nested-background", state.get("observation"));
            } else {
                assertThrows(IllegalStateException.class,
                    () -> PortalClippingTestControl.requireCurrentDepth("minecraft:the_end:2"));
            }
        }
        assertEquals(3, captures);
        assertEquals(3L, PortalClippingTestControl.evidence().get("depthReadbacks"));
        assertEquals(15L, PortalClippingTestControl.evidence().get("depthReadbackNanos"));
    }

    @Test
    void oldFramesScenesAndMissingNestedViewsCannotSupplyDepth() {
        PortalClippingTestControl.install("");
        PortalClippingTestControl.beginObservation("before-reload:nested-background");
        PortalClippingTestControl.beginFrame(120, true);
        PortalClippingTestControl.recordInnerDepth("minecraft:the_end:2", java.util.Map.of("sampleCount", 81));
        assertDoesNotThrow(() -> PortalClippingTestControl.requireCurrentDepth("minecraft:the_end:2"));
        PortalClippingTestControl.beginFrame(130, true);
        PortalClippingTestControl.recordInnerDepth("minecraft:the_nether:1", java.util.Map.of("sampleCount", 81));
        assertThrows(IllegalStateException.class,
            () -> PortalClippingTestControl.requireCurrentDepth("minecraft:the_end:2"));
        PortalClippingTestControl.beginObservation("after-reload:nested-background");
        assertFalse(PortalClippingTestControl.observesInnerDepth());
        assertThrows(IllegalStateException.class,
            () -> PortalClippingTestControl.requireCurrentDepth("minecraft:the_nether:1"));
        PortalClippingTestControl.recordInnerDepth("minecraft:the_end:2", java.util.Map.of("sampleCount", 81));
        assertTrue(((java.util.Map<?, ?>) PortalClippingTestControl.evidence().get("innerWorldDepthStates")).isEmpty());
        PortalClippingTestControl.beginFrame(140, true);
        PortalClippingTestControl.recordInnerDepth("minecraft:the_end:2", java.util.Map.of("sampleCount", 81));
        assertEquals("after-reload:nested-background",
            PortalClippingTestControl.requireCurrentDepth("minecraft:the_end:2").get("observation"));
        assertThrows(IllegalArgumentException.class, () -> PortalClippingTestControl.beginFrame(140, true));
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
