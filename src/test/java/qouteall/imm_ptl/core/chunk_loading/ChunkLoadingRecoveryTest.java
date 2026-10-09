package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.server.level.ChunkResult;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChunkLoadingRecoveryTest {
    @Test void failedNeighborRangeRequeuesUntilItRecoversWithoutReplacingSuccess() {
        CompletableFuture<ChunkResult<String>> future = CompletableFuture.completedFuture(ChunkResult.error("unloaded neighbors"));
        var attempts = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            future = ChunkLoadingRecovery.retry(future, () -> CompletableFuture.completedFuture(
                attempts.incrementAndGet() < 2 ? ChunkResult.error("still unloading") : ChunkResult.of("real chunk")));
        }
        assertEquals("real chunk", future.join().orElse(null));
        assertEquals(2, attempts.get(), "successful futures must not be reset or regenerated");
    }

    @Test void pendingFuturesArePreservedAndExceptionalIoIsNotMasked() {
        var pending = new CompletableFuture<ChunkResult<String>>();
        assertSame(pending, ChunkLoadingRecovery.retry(pending, () -> { fail("pending work must not restart"); return null; }));
        var exceptional = CompletableFuture.<ChunkResult<String>>failedFuture(new IllegalStateException("disk error"));
        var error = assertThrows(CompletionException.class, () -> ChunkLoadingRecovery.retry(exceptional,
            () -> { fail("exceptional IO must remain visible"); return null; }));
        assertEquals("disk error", error.getCause().getMessage());
    }

    @Test void repeatedPendingSendsCannotPostponeRetryAndFailuresRespectCooldown() {
        var retry = new ChunkLoadingRecovery.Retry();
        assertTrue(retry.schedule(100));
        assertFalse(retry.schedule(119));
        assertFalse(retry.ready(119));
        assertTrue(retry.ready(120));
        retry.started();
        assertFalse(retry.scheduled());
        assertTrue(retry.schedule(120));
        assertFalse(retry.ready(139));
        assertTrue(retry.ready(140));
    }
}
