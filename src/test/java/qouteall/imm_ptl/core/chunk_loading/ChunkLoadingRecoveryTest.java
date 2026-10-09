package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.server.level.ChunkResult;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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

    @Test void disabledRegistrationDoesNotConsumeFourThrottleSlotsOrLoseQueuedWork() {
        var queue = new LongLinkedOpenHashSet();
        var waiting = new LongOpenHashSet();
        for (long chunkPos = 1; chunkPos <= 5; chunkPos++) {
            var info = new ImmPtlChunkTickets.ChunkTicketInfo(1, 0);
            assertFalse(ImmPtlChunkTickets.recordTicketAttempt(queue, waiting, chunkPos, info, false, 100));
            assertFalse(info.ticketAdded);
            assertTrue(queue.contains(chunkPos));
            assertEquals(0, waiting.size());
        }
        var enabled = new ImmPtlChunkTickets.ChunkTicketInfo(1, 0);
        assertTrue(ImmPtlChunkTickets.recordTicketAttempt(queue, waiting, 6, enabled, true, 110));
        assertTrue(enabled.ticketAdded);
        assertEquals(1, waiting.size());
        assertTrue(waiting.contains(6));
    }

    @Test void aMissingHolderReleasesItsSlotAfterBoundedWait() {
        var queue = new LongLinkedOpenHashSet();
        var waiting = new LongOpenHashSet();
        var info = new ImmPtlChunkTickets.ChunkTicketInfo(1, 0);
        assertTrue(ImmPtlChunkTickets.recordTicketAttempt(queue, waiting, 42, info, true, 100));
        assertFalse(ImmPtlChunkTickets.missingHolderWaitExpired(info, 299));
        assertTrue(ImmPtlChunkTickets.missingHolderWaitExpired(info, 300));
        assertTrue(info.retry.schedule(300));
        assertFalse(info.retry.ready(319));
        assertTrue(info.retry.ready(320));
        assertTrue(waiting.remove(42));
        assertEquals(0, waiting.size());
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
