package qouteall.imm_ptl.core.gametest.sablee2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class SableDimensionStackIntegrationMarkers {
    static final String ENABLE_PROPERTY = "ip.sable.e2e";
    static final String ENABLE_ENV = "IP_SABLE_E2E";
    static final String RESULT_DIR_PROPERTY = "ip.sable.e2e.resultDir";
    static final String RESULT_DIR_ENV = "IP_SABLE_E2E_RESULT_DIR";

    private SableDimensionStackIntegrationMarkers() {}

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY) || "true".equalsIgnoreCase(System.getenv(ENABLE_ENV));
    }

    static void serverPass(String detail) {
        requireMarker("client-source.txt");
        requireMarker("client-destination.txt");
        requireMarker("client-return.txt");
        requireMarker("client-recross.txt");
        requireMarker("client-dismount.txt");
        requireMarker("client-remote-source.txt");
        requireMarker("client-remote-moved.txt");
        write("server-pass.txt", detail, null);
    }

    static void serverFail(String detail, Throwable error) {
        write("server-fail.txt", detail, error);
    }

    static void clientPass(String detail) {
        requireMarker("server-pass.txt");
        requireMarker("client-remote-source.txt");
        requireMarker("client-remote-moved.txt");
        write("client-pass.txt", detail, null);
    }

    static void clientFail(String detail, Throwable error) {
        write("client-fail.txt", detail, error);
    }

    static void acknowledge(String phase) {
        write("client-" + phase + ".txt", phase, null);
    }

    static boolean exists(String file) {
        return Files.isRegularFile(resultDir().resolve(file));
    }

    private static void requireMarker(String file) {
        Path marker = resultDir().resolve(file);
        if (!Files.isRegularFile(marker)) {
            throw new IllegalStateException("Required Sable dimension-stack E2E marker is missing: " + file);
        }
        try {
            if (Files.readString(marker, StandardCharsets.UTF_8).isBlank()) {
                throw new IllegalStateException("Required Sable dimension-stack E2E marker is empty: " + file);
            }
        }
        catch (IOException io) {
            throw new IllegalStateException("Cannot read required Sable dimension-stack E2E marker " + file, io);
        }
    }

    private static Path resultDir() {
        String resultDir = System.getProperty(RESULT_DIR_PROPERTY);
        if (resultDir == null || resultDir.isBlank()) {
            resultDir = System.getenv(RESULT_DIR_ENV);
        }
        if (resultDir == null || resultDir.isBlank()) {
            resultDir = "build/sable-dimension-stack-e2e";
        }

        return Paths.get(resultDir);
    }

    private static void write(String file, String detail, Throwable error) {
        StringBuilder text = new StringBuilder(detail).append('\n');
        if (error != null) {
            text.append(error).append('\n');
        }

        try {
            Path dir = resultDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(file), text.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException io) {
            throw new IllegalStateException("Cannot write Sable dimension-stack E2E marker " + file, io);
        }

        System.out.println("[Immersive Portals Sable E2E] " + detail);
        if (error != null) {
            error.printStackTrace();
        }
    }
}
