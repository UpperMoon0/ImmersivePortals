package qouteall.imm_ptl.core.gametest.sablee2e;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import static org.junit.jupiter.api.Assertions.*;

class PortalSmokeSupportTest {
    @TempDir Path directory;

    @Test void publishesCompleteReplacement() throws Exception {
        PortalSmokeSupport.write(directory, "marker.txt", "old");
        PortalSmokeSupport.write(directory, "marker.txt", "complete new result");
        assertEquals("complete new result", Files.readString(directory.resolve("marker.txt")));
        assertFalse(Files.exists(directory.resolve("marker.txt.tmp")));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void transientReaderLockDoesNotFailTheSession() throws Exception {
        PortalSmokeSupport.write(directory, "marker.txt", "old");
        FileChannel reader = FileChannel.open(directory.resolve("marker.txt"), StandardOpenOption.READ,
            ExtendedOpenOption.NOSHARE_DELETE);
        var ready = new java.util.concurrent.CountDownLatch(1);
        Thread release = Thread.ofPlatform().start(() -> {
            ready.countDown();
            try { Thread.sleep(10); reader.close(); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        ready.await();
        try { PortalSmokeSupport.write(directory, "marker.txt", "new"); }
        finally { release.join(); reader.close(); }
        assertEquals("new", Files.readString(directory.resolve("marker.txt")));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void persistentReaderLockStillFailsAndPreservesOldResult() throws Exception {
        PortalSmokeSupport.write(directory, "marker.txt", "old");
        try (FileChannel reader = FileChannel.open(directory.resolve("marker.txt"), StandardOpenOption.READ,
            ExtendedOpenOption.NOSHARE_DELETE)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PortalSmokeSupport.write(directory, "marker.txt", "new"));
            assertInstanceOf(AccessDeniedException.class, failure.getCause());
            assertTrue(failure.getMessage().contains("marker.txt"));
            assertEquals("old", Files.readString(directory.resolve("marker.txt")));
        }
    }
}
