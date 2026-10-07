package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/** Isolated development-only live shadow-map evidence. Excluded from release jars. */
public final class PortalShadowTestControl {
    private static String observation = "";
    private static Map<String, Object> latest = Map.of();
    private static long observations;
    private static Map<String, Object> wholeMap = Map.of();
    private static final Map<String, Object> drawStates = new LinkedHashMap<>();
    private static long terrainSetups;
    private static Map<String, Object> rendererState = Map.of();

    private PortalShadowTestControl() {}
    public static boolean enabled() { return "true".equals(System.getenv("IP_SHADOW_SMOKE")); }
    public static boolean negative() { return "shadow-clipping-enabled".equals(System.getenv("IP_SMOKE_NEGATIVE_CONTROL")); }
    public static boolean poison() { return negative() && observation.endsWith(":caster"); }
    public static void begin(String scene) { observation = scene; latest = Map.of(); observations = 0; wholeMap = Map.of(); drawStates.clear(); terrainSetups = 0; rendererState = Map.of(); }
    public static Map<String, Object> evidence() { return new LinkedHashMap<>(latest); }
    public static void recordRenderer(Map<String, Object> state) { rendererState = state; }
    public static void recordRegion(float x, float y, float z) {
        if (!enabled() || observation.isEmpty() || !net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE
            || !qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) return;
        terrainSetups++;
        int program = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        boolean clipEnabled = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
        float probe = PortalShadowOracle.clipProbe(clipEnabled);
        int probeLocation = org.lwjgl.opengl.GL20.glGetUniformLocation(program, "ip_ShadowClipProbe");
        if (probeLocation < 0) throw new IllegalStateException("Owned shadow fixture probe uniform is absent");
        // Never infer scope success from a Java flag: the rendered sentinel follows the actual GL bit.
        // Mesa llvmpipe also clipped runtime-negative outputs with that bit disabled in a minimal
        // EGL reproduction. Neutral output in the disabled state avoids that fixture artifact.
        org.lwjgl.opengl.GL20.glUniform1f(probeLocation, probe);
        String key = program + ":" + x + ":" + y + ":" + z;
        if (drawStates.containsKey(key) || drawStates.size() >= 8) return;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("program", program);
        state.put("regionOffset", new float[]{x, y, z});
        state.put("clipDistanceEnabled", clipEnabled);
        state.put("probeOutput", probe);
        int cameraLocation = org.lwjgl.opengl.GL20.glGetUniformLocation(program, "cameraPosition");
        if (cameraLocation >= 0) {
            float[] camera = new float[3];
            org.lwjgl.opengl.GL20.glGetUniformfv(program, cameraLocation, camera);
            state.put("cameraPosition", camera);
        }
        drawStates.put(key, state);
    }

    public static void capture(int texture, int resolution, boolean inheritedClippingRestored) {
        if (observation.isEmpty()) return;
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int pbo = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int rows = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int swap = GL11.glGetInteger(GL11.GL_PACK_SWAP_BYTES);
        int framebuffer = GL30.glGenFramebuffers();
        float[] samples = new float[25];
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glReadBuffer(GL11.GL_NONE);
            GL11.glDrawBuffer(GL11.GL_NONE);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Shadow acceptance framebuffer is incomplete");
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, 0);
            GL11.glReadPixels(resolution / 2 - 2, resolution / 2 - 2, 5, 5,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, samples);
            if (observations == 119) {
                float[] full = new float[resolution * resolution];
                GL11.glReadPixels(0, 0, resolution, resolution, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, full);
                int count = 0, minX = resolution, minY = resolution, maxX = -1, maxY = -1;
                float minimum = 1;
                for (int y = 0; y < resolution; y++) for (int x = 0; x < resolution; x++) {
                    float value = full[y * resolution + x];
                    if (value < 0.9999f) {
                        count++; minimum = Math.min(minimum, value);
                        minX = Math.min(minX, x); minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                    }
                }
                wholeMap = Map.of("non_clear_pixels", count, "minimum", minimum,
                    "bounds", new int[]{minX, minY, maxX, maxY}, "observation_number", observations + 1);
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, swap);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL30.glDeleteFramebuffers(framebuffer);
        }
        float[] sorted = samples.clone();
        Arrays.sort(sorted);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("observation", observation);
        state.put("observations", ++observations);
        state.put("resolution", resolution);
        state.put("sample_count", samples.length);
        state.put("minimum", sorted[0]);
        state.put("median", sorted[12]);
        state.put("maximum", sorted[24]);
        state.put("samples", samples);
        state.put("whole_map", wholeMap);
        state.put("terrain_region_setups", terrainSetups);
        state.put("terrain_draw_states", new LinkedHashMap<>(drawStates));
        state.put("renderer_state", rendererState);
        state.put("inherited_clipping_restored", inheritedClippingRestored);
        state.put("negative_control", negative());
        latest = state;
    }
}
