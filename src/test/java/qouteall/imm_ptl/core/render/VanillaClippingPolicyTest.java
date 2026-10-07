package qouteall.imm_ptl.core.render;

import org.junit.jupiter.api.Test;
import me.shedaniel.cloth.clothconfig.shadowed.org.yaml.snakeyaml.Yaml;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class VanillaClippingPolicyTest {
    @Test
    void deferredVanillaEntityParticleAndDamagePathsGetDrawScopes() {
        for (String shader : new String[]{"rendertype_crumbling", "particle", "rendertype_beacon_beam",
            "rendertype_entity_solid", "rendertype_entity_cutout", "rendertype_entity_translucent",
            "rendertype_item_entity_translucent_cull"}) {
            assertTrue(VanillaClippingPolicy.needsDrawScope(shader), shader);
        }
    }

    @Test
    void drawScopeAllowlistMatchesTheActualCameraRelativeYamlRule() throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/immersive_portals/shaders/shader_transformation.yaml")) {
            assertNotNull(input);
            Map<?, ?> yaml = new Yaml().load(input);
            List<?> configs = (List<?>) yaml.get("configs");
            Set<String> expected = new HashSet<>();
            for (Object item : configs) {
                Map<?, ?> config = (Map<?, ?>) item;
                List<?> names = (List<?>) config.get("affectedShaders");
                if (names.contains("particle")) {
                    for (Object name : names) {
                        if (!name.equals("portal_area")) expected.add((String) name);
                    }
                }
            }
            assertEquals(expected, VanillaClippingPolicy.WORLD_SHADER_NAMES);
        }
    }

    @Test
    void latePortalDrawsStartAScopeButExistingOuterPlanesArePreserved() {
        assertTrue(VanillaClippingPolicy.shouldStartPortalScope(true, false));
        assertFalse(VanillaClippingPolicy.shouldStartPortalScope(true, true));
        assertFalse(VanillaClippingPolicy.shouldStartPortalScope(false, true));
        assertFalse(VanillaClippingPolicy.shouldStartPortalScope(false, false));
    }

    @Test
    void portalAperturesTerrainAndFullScreenProgramsKeepTheirExistingScopePolicy() {
        for (String shader : new String[]{"portal_area", "rendertype_solid", "rendertype_cutout",
            "position_tex", "blit_screen", "final", "crumbling", "entities_solid", "particles", "shadow",
            "rendertype_entity_unknown", "rendertype_item_entity_unknown"}) {
            assertFalse(VanillaClippingPolicy.needsDrawScope(shader), shader);
        }
    }
}
