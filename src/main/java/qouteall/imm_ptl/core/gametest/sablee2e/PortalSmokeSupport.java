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
    static int samples() { return Integer.parseInt(System.getenv().getOrDefault("IP_SMOKE_SAMPLES", "200")); }
    static void write(String name, String text) {
        try {
            Files.createDirectories(directory());
            Files.writeString(directory().resolve(name), text);
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
