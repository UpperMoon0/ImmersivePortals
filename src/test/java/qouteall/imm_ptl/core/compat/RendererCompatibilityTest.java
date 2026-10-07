package qouteall.imm_ptl.core.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RendererCompatibilityTest {
    private static final String PREFIX = "qouteall.imm_ptl.core.compat.mixin.";

    @Test
    void everyModIdCombinationIsUnambiguousOrActionablyRejected() {
        for (int mask = 0; mask < 16; mask++) {
            boolean sodium = (mask & 1) != 0, embeddium = (mask & 2) != 0;
            boolean iris = (mask & 4) != 0, oculus = (mask & 8) != 0;
            boolean supported = !(sodium && embeddium) && (!oculus || embeddium)
                && (oculus || !iris || sodium);
            if (!supported) {
                var error = assertThrows(IllegalStateException.class,
                    () -> RendererCompatibility.select(sodium, embeddium, iris, oculus));
                assertTrue(error.getMessage().startsWith("Immersive Portals renderer compatibility:"));
                continue;
            }
            var backend = RendererCompatibility.select(sodium, embeddium, iris, oculus);
            assertEquals(sodium, backend.appliesTo(PREFIX + "sodium.MixinSodiumWorldRenderer"));
            assertEquals(embeddium, backend.appliesTo(PREFIX + "embeddium.MixinEmbeddiumWorldRenderer"));
            assertEquals(iris && !oculus, backend.appliesTo(PREFIX + "iris.MixinIrisSodiumShader"));
            assertEquals(oculus, backend.appliesTo(PREFIX + "neoculus.MixinNeOculusEmbeddiumShader"));
            assertEquals(iris || oculus, backend.appliesTo(PREFIX + "iris.MixinIrisRenderingPipeline"));
        }
    }

    @Test
    void dummyIrisIdCannotSelectSodiumShaderOrUnauditedIrisTargets() {
        var backend = RendererCompatibility.select(false, true, true, true);
        assertFalse(backend.appliesTo(PREFIX + "iris.MixinIrisSodiumShader"));
        assertFalse(backend.appliesTo(PREFIX + "iris.MixinIrisFutureSodiumTarget"));
        assertTrue(backend.appliesTo(PREFIX + "iris.MixinIrisWorldShader"));
        assertTrue(backend.appliesTo(PREFIX + "iris.MixinIrisShaderCreator"));
        assertTrue(backend.appliesTo(PREFIX + "iris.MixinIrisVertexBuffer"));
        assertTrue(backend.appliesTo(PREFIX + "iris.MixinIrisShadowRenderer"));
        assertTrue(backend.appliesTo(PREFIX + "iris.MixinIrisFullScreenQuadRenderer"));
        assertFalse(backend.appliesTo(PREFIX + "iris.MixinIrisParticleEngine"));
    }

    @Test
    void unsupportedBackendVersionsFailBeforeOptionalClassesLoad() {
        var backend = RendererCompatibility.select(false, true, true, true);
        assertDoesNotThrow(() -> backend.checkVersions("1.0.15+mc1.21.1", "1.8.7"));
        assertDoesNotThrow(() -> backend.checkVersions("1.0.15", "1.8.7"));
        assertTrue(assertThrows(IllegalStateException.class,
            () -> backend.checkVersions("1.0.16", "1.8.7")).getMessage().contains("1.0.15+mc1.21.1"));
        assertTrue(assertThrows(IllegalStateException.class,
            () -> backend.checkVersions("1.0.15", "1.8.6")).getMessage().contains("NeOculus 1.8.7"));
        assertDoesNotThrow(() -> RendererCompatibility.select(false, false, false, false).checkVersions(null, null));
    }
}
