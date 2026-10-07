package qouteall.imm_ptl.core.render;

import java.util.Set;

/** Vanilla world programs transformed by the YAML rules, excluding portal_area. */
public final class VanillaClippingPolicy {
    static final Set<String> WORLD_SHADER_NAMES = Set.of(
        // Create also uses chunk render types for camera-relative contraptions and block entities.
        // The terrain YAML rule includes ChunkOffset, so both zero-offset buffered geometry and
        // chunk-local geometry use the same before-model-view clipping equation.
        "rendertype_solid", "rendertype_cutout", "rendertype_cutout_mipped", "rendertype_translucent",
        "rendertype_entity_solid", "rendertype_entity_cutout", "rendertype_entity_cutout_no_cull",
        "rendertype_entity_cutout_no_cull_z_offset", "rendertype_item_entity_translucent_cull",
        "rendertype_entity_translucent_cull", "rendertype_entity_translucent", "rendertype_entity_smooth_cutout",
        "rendertype_beacon_beam", "rendertype_entity_translucent_emissive", "particle", "rendertype_crumbling"
    );

    private VanillaClippingPolicy() {}

    public static boolean needsDrawScope(String shaderName) {
        return WORLD_SHADER_NAMES.contains(shaderName);
    }

    public static boolean shouldStartPortalScope(boolean portalRendering, boolean clippingAlreadyEnabled) {
        // Preserve an existing outer/entity clipping plane rather than replacing it.
        return portalRendering && !clippingAlreadyEnabled;
    }
}
