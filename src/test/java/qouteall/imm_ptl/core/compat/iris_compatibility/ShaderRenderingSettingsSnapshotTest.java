package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShaderRenderingSettingsSnapshotTest {
    private static final class ProviderSettings {
        private static final Object INSTANCE_MARKER = new Object();
        private boolean reloadRequired;
        private Object chunkVertexFormat;
        private Map<String, Integer> blockStateIds;
        private boolean useExtendedVertexFormat;
        private float ambientOcclusionLevel;
    }

    @Test
    void replayUsesOriginalFormatAndMappingsThenRestoresAllGlobalSettingsEvenOnFailure() {
        var settings = new ProviderSettings();
        Object originalFormat = new Object();
        var originalMappings = Map.of("nether", 3);
        settings.chunkVertexFormat = originalFormat;
        settings.blockStateIds = originalMappings;
        settings.reloadRequired = true;
        settings.useExtendedVertexFormat = true;
        settings.ambientOcclusionLevel = 0.25F;
        var request = ShaderRenderingSettingsSnapshot.capture(settings);

        Object outerFormat = new Object();
        var outerMappings = Map.of("overworld", 7);
        settings.chunkVertexFormat = outerFormat;
        settings.blockStateIds = outerMappings;
        settings.reloadRequired = false;
        settings.useExtendedVertexFormat = false;
        settings.ambientOcclusionLevel = 1F;
        var previous = ShaderRenderingSettingsSnapshot.capture(settings);
        Object singleton = ProviderSettings.INSTANCE_MARKER;
        assertThrows(IllegalStateException.class, () -> {
            try {
                request.restore();
                assertSame(originalFormat, settings.chunkVertexFormat);
                assertSame(originalMappings, settings.blockStateIds);
                assertTrue(settings.reloadRequired);
                assertTrue(settings.useExtendedVertexFormat);
                assertEquals(0.25F, settings.ambientOcclusionLevel);
                throw new IllegalStateException("reload failed");
            }
            finally {
                previous.restore();
            }
        });
        assertSame(outerFormat, settings.chunkVertexFormat);
        assertSame(outerMappings, settings.blockStateIds);
        assertFalse(settings.reloadRequired, "Do not resurrect the provider's consumed one-shot flag");
        assertFalse(settings.useExtendedVertexFormat);
        assertEquals(1F, settings.ambientOcclusionLevel);
        assertSame(singleton, ProviderSettings.INSTANCE_MARKER);
    }
}
