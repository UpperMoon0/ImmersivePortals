package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.LinkedHashMap;
import java.util.Map;

/** Development-only targeted shader negatives and observed draw evidence. Excluded from release jars. */
public final class PortalClippingTestControl {
    private static String negativeControl = "";
    private static boolean installed;
    private static String observation = "";
    private static long frame;
    private static boolean captureDepth;
    private static long depthReadbacks, depthReadbackNanos;
    private static final Map<String, Integer> depthObservationCounts = new LinkedHashMap<>();
    private static final Map<String, Integer> selected = new LinkedHashMap<>();
    private static final Map<String, Integer> bypassed = new LinkedHashMap<>();
    private static final Map<String, String> sources = new LinkedHashMap<>();
    private static final Map<String, Map<String, Integer>> draws = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> terrainStates = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> bufferedDrawStates = new LinkedHashMap<>();
    private static final Map<String, Map<String, Object>> innerDepthStates = new LinkedHashMap<>();

    private PortalClippingTestControl() {}

    /** Call once before the initial shader reload. No production environment/system-property hook exists. */
    public static synchronized void install(String control) {
        negativeControl = control == null ? "" : control;
        installed = true;
        selected.clear();
        bypassed.clear();
        sources.clear();
        draws.clear();
        terrainStates.clear();
        bufferedDrawStates.clear();
        innerDepthStates.clear();
        observation = "";
        frame = 0;
        captureDepth = false;
        depthReadbacks = depthReadbackNanos = 0;
        depthObservationCounts.clear();
    }

    /** Keep compile evidence across scenes; clear draw evidence before each observed scene. */
    public static synchronized void beginObservation(String name) {
        observation = name;
        captureDepth = false;
        depthReadbacks = depthReadbackNanos = 0;
        depthObservationCounts.clear();
        draws.clear();
        terrainStates.clear();
        bufferedDrawStates.clear();
        innerDepthStates.clear();
    }

    public static synchronized boolean select(String patch, String name, boolean productionSelection) {
        if (!installed || !productionSelection) return productionSelection;
        String key = patch + ":" + name;
        selected.merge(key, 1, Integer::sum);
        boolean disable = patch.equals("VANILLA") && (
            negativeControl.equals("entity-clipping-disabled") && targets("entity", name)
            || negativeControl.equals("particle-clipping-disabled") && targets("particle", name)
        );
        if (disable) bypassed.merge(key, 1, Integer::sum);
        return !disable;
    }

    public static synchronized void recordSource(String drawName, String sourceName) {
        if (installed) sources.put(drawName, sourceName);
    }

    /** Called after the actual VertexBuffer draw completes, not merely at shader construction. */
    public static synchronized void recordDraw(String drawName, boolean hasUniform, boolean portal) {
        if (!installed || !(targets("entity", drawName) || targets("particle", drawName))) return;
        String key = (portal ? "portal" : "outer") + (hasUniform ? "WithUniform" : "WithoutUniform");
        draws.computeIfAbsent(drawName, ignored -> new LinkedHashMap<>()).merge(key, 1, Integer::sum);
    }

    /** Called before rendering: every accepted depth witness must come from this exact frame. */
    public static synchronized void beginFrame(long frameNumber, boolean assertionFrame) {
        if (frameNumber <= frame) throw new IllegalArgumentException("Render frame must advance");
        frame = frameNumber;
        captureDepth = assertionFrame;
        innerDepthStates.clear();
    }

    public static boolean assertionFrame(int sceneFrame) {
        return sceneFrame >= 120 && sceneFrame % 10 == 0;
    }

    public static synchronized boolean observesInnerDepth() {
        return installed && captureDepth && !observation.isEmpty();
    }

    public static synchronized boolean observesNativeDepth() {
        return installed && observation.endsWith(":crossing");
    }

    public static synchronized void recordInnerDepth(String key, Map<String, Object> state) {
        if (!observesInnerDepth()) return;
        Map<String, Object> copy = new LinkedHashMap<>(state);
        copy.put("observationCount", depthObservationCounts.merge(key, 1, Integer::sum));
        copy.put("observation", observation);
        copy.put("frame", frame);
        innerDepthStates.put(key, copy);
        depthReadbacks++;
        if (state.get("readbackNanos") instanceof Number nanos) depthReadbackNanos += nanos.longValue();
    }

    /** Refuse old scene/phase/frame data, including when a target view did not render this frame. */
    public static synchronized Map<String, Object> requireCurrentDepth(String key) {
        Map<String, Object> state = innerDepthStates.get(key);
        if (!observesInnerDepth() || state == null || !observation.equals(state.get("observation"))
            || !(state.get("frame") instanceof Number captured) || captured.longValue() != frame) {
            throw new IllegalStateException("Missing current scene/frame depth witness for " + key
                + " at " + observation + " frame " + frame);
        }
        return new LinkedHashMap<>(state);
    }

    public static synchronized boolean needsBufferedDrawState(String key) {
        return installed && !observation.isEmpty() && !bufferedDrawStates.containsKey(key) && bufferedDrawStates.size() < 96;
    }

    public static synchronized void recordBufferedDrawState(String key, Map<String, Object> state) {
        bufferedDrawStates.putIfAbsent(key, new LinkedHashMap<>(state));
    }

    public static synchronized boolean needsTerrainState(String key) {
        return installed && !observation.isEmpty() && !terrainStates.containsKey(key) && terrainStates.size() < 48;
    }

    public static synchronized void recordTerrainState(String key, Map<String, Object> state) {
        terrainStates.putIfAbsent(key, new LinkedHashMap<>(state));
    }

    public static synchronized Map<String, Object> evidence() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("installed", installed);
        result.put("negativeControl", negativeControl);
        result.put("observation", observation);
        result.put("frame", frame);
        result.put("depthReadbacks", depthReadbacks);
        result.put("depthReadbackNanos", depthReadbackNanos);
        result.put("selectedProgramCounts", new LinkedHashMap<>(selected));
        result.put("bypassedProgramCounts", new LinkedHashMap<>(bypassed));
        result.put("sourceByDrawName", new LinkedHashMap<>(sources));
        Map<String, Map<String, Integer>> drawCopy = new LinkedHashMap<>();
        draws.forEach((name, counts) -> drawCopy.put(name, new LinkedHashMap<>(counts)));
        result.put("completedDrawCounts", drawCopy);
        result.put("terrainUniformStates", new LinkedHashMap<>(terrainStates));
        result.put("bufferedDrawStates", new LinkedHashMap<>(bufferedDrawStates));
        result.put("innerWorldDepthStates", new LinkedHashMap<>(innerDepthStates));
        return result;
    }

    /** Fail rather than accepting a negative control that never compiled and drew its target path. */
    public static synchronized void assertTargetDrawn(String target, boolean expectClipping) {
        boolean observed = draws.entrySet().stream().anyMatch(entry -> {
            String name = entry.getKey();
            String key = "VANILLA:" + name;
            return targets(target, name) && sources.containsKey(name) && selected.getOrDefault(key, 0) > 0
                && (expectClipping || bypassed.getOrDefault(key, 0) > 0)
                && entry.getValue().getOrDefault(expectClipping ? "portalWithUniform" : "portalWithoutUniform", 0) > 0;
        });
        if (!observed) {
            throw new IllegalStateException("No compiled and completed portal " + target
                + " draw with expected clipping=" + expectClipping + ": " + evidence());
        }
    }

    private static boolean targets(String target, String name) {
        return switch (target) {
            case "entity" -> name.startsWith("entities_");
            case "particle" -> name.equals("particles") || name.equals("particles_trans");
            default -> throw new IllegalArgumentException("Unknown clipping-test target " + target);
        };
    }
}
