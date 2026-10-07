package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class SableMarkerPublicationTest {
    @TempDir Path directory;

    @Test
    @Timeout(20)
    void concurrentConsumerNeverSeesAnEmptyOrPartialPublishedMarker() throws Exception {
        int count = 200;
        String detail = "complete result\n" + "x".repeat(64 * 1024);
        var producer = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < count; i++)
                    SableDimensionStackIntegrationMarkers.publish(directory.resolve(i + ".txt"), detail);
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        });
        for (int i = 0; i < count; i++) {
            Path marker = directory.resolve(i + ".txt");
            while (!Files.isRegularFile(marker)) {
                if (producer.isDone()) producer.join();
                Thread.onSpinWait();
            }
            assertEquals(detail, Files.readString(marker), "Visible marker must be fully written: " + i);
        }
        producer.join();
        try (var files = Files.list(directory)) {
            assertEquals(count, files.count(), "Temporary files must be cleaned up");
        }
    }
}
