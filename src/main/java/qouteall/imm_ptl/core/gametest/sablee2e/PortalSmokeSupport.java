package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class PortalSmokeSupport {
    static boolean enabled() { return "true".equals(System.getenv("IP_PORTAL_SMOKE")); }
    static Path directory() { return Path.of(System.getenv("IP_SABLE_E2E_RESULT_DIR")); }
    static boolean exists(String name) { return Files.isRegularFile(directory().resolve(name)); }
    static String read(String name) {
        try { return exists(name) ? Files.readString(directory().resolve(name)).strip() : ""; }
        catch (Exception e) { throw new IllegalStateException("Cannot read smoke request", e); }
    }
    static boolean activeShaders() {
        return System.getenv().getOrDefault("IP_SMOKE_RENDERER", "sodium").endsWith("-active");
    }
    static boolean diagnosticFixture() { return !"false".equals(System.getenv("IP_SMOKE_DIAGNOSTIC_FIXTURE")); }
    static List<String> scenes() {
        boolean references = activeShaders() && !diagnosticFixture();
        var result = new java.util.ArrayList<String>();
        if (references) result.add("solid-background");
        result.addAll(List.of("solid-visible", "solid-clipped"));
        if (activeShaders() && diagnosticFixture()) {
            for (String program : List.of("cutout", "translucent", "entity", "block-entity", "particle")) {
                result.add(program + "-visible");
                result.add(program + "-clipped");
            }
        }
        // Compatibility/debug renderers deliberately support one portal layer only.
        if (System.getenv().getOrDefault("IP_SMOKE_RENDER_MODE", "normal").equals("normal")) {
            if (references) result.addAll(List.of("nested-background", "nested-visible"));
            result.add("nested");
            if (references) result.add("create-nested-background");
            result.add("create-nested");
        }
        if (references) result.addAll(List.of("mirror-background", "mirror-visible"));
        result.add("mirror");
        if (references) result.add("create-background");
        result.addAll(List.of("create-visible", "create-clipped",
            "create-crumbling-clean", "create-crumbling-damaged", "create-crumbling-clipped"));
        return result;
    }
    static int samples() { return Integer.parseInt(System.getenv().getOrDefault("IP_SMOKE_SAMPLES", "200")); }
    static void write(String name, String text) {
        try {
            Files.createDirectories(directory());
            Path temporary = directory().resolve(name + ".tmp");
            Files.writeString(temporary, text);
            try {
                Files.move(temporary, directory().resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, directory().resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot write smoke result", e); }
    }
    static void metrics(String side, List<Double> milliseconds) {
        double[] sorted = milliseconds.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        write(side + "-metrics.json", new Gson().toJson(Map.of(
            "samples", sorted.length,
            "p95_ms", sorted[(int) Math.ceil(sorted.length * 0.95) - 1],
            "max_ms", sorted[sorted.length - 1],
            "heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        )));
    }
}
