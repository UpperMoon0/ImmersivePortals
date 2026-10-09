package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.server.level.ChunkResult;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
            assertFalse(ImmPtlChunkTickets.recordTicketAttempt(queue, waiting, chunkPos, info, false, 1, 100));
            assertFalse(info.ticketAdded);
            assertEquals(0, info.installedTicketRadius);
            assertTrue(queue.contains(chunkPos));
            assertEquals(0, waiting.size());
        }
        var enabled = new ImmPtlChunkTickets.ChunkTicketInfo(1, 0);
        assertTrue(ImmPtlChunkTickets.recordTicketAttempt(queue, waiting, 6, enabled, true, 1, 110));
        assertTrue(enabled.ticketAdded);
        assertEquals(1, enabled.installedTicketRadius);
        assertEquals(1, waiting.size());
        assertTrue(waiting.contains(6));
    }

    @Test void aMissingHolderReleasesItsSlotAfterBoundedWait() {
        var queue = new LongLinkedOpenHashSet();
        var waiting = new LongOpenHashSet();
        var info = new ImmPtlChunkTickets.ChunkTicketInfo(1, 0);
        assertTrue(ImmPtlChunkTickets.recordTicketAttempt(queue, waiting, 42, info, true, 1, 100));
        assertFalse(ImmPtlChunkTickets.missingHolderWaitExpired(info, 299));
        assertTrue(ImmPtlChunkTickets.missingHolderWaitExpired(info, 300));
        assertTrue(info.retry.schedule(300));
        assertFalse(info.retry.ready(319));
        assertTrue(info.retry.ready(320));
        assertTrue(waiting.remove(42));
        assertEquals(0, waiting.size());
    }


    @Test void fourWaitingChunksUpgradeTheirTicketsWhenLiveLoadingModeChanges() {
        var queue = new LongLinkedOpenHashSet();
        var waiting = new LongOpenHashSet();
        Map<Long, ImmPtlChunkTickets.ChunkTicketInfo> infos = new HashMap<>();
        Map<Long, Set<Integer>> installed = new HashMap<>();

        // Radius 1 -> ticket level 32 (BLOCK_TICKING). All four throttle slots are occupied.
        assertEquals(32, ImmPtlChunkTickets.ticketLevelForRadius(1));
        assertEquals(31, ImmPtlChunkTickets.ticketLevelForRadius(2));
        assertTrue(ImmPtlChunkTickets.hasRequiredTicketLevel(32, 1));
        assertFalse(ImmPtlChunkTickets.hasRequiredTicketLevel(32, 2));
        assertTrue(ImmPtlChunkTickets.hasRequiredTicketLevel(31, 2));
        assertEquals(1, ImmPtlChunkTickets.radiusFromTicketLevel(32));
        assertEquals(2, ImmPtlChunkTickets.radiusFromTicketLevel(31));
        for (long pos = 1; pos <= 4; pos++) {
            var info = new ImmPtlChunkTickets.ChunkTicketInfo(1, 0);
            infos.put(pos, info);
            installed.put(pos, new HashSet<>(Set.of(1)));
            assertTrue(ImmPtlChunkTickets.recordTicketAttempt(queue, waiting, pos, info, true, 1, 100));
            assertEquals(1, info.installedTicketRadius);
        }
        assertEquals(4, waiting.size());

        // A live change of serverSideNormalChunkLoading to true requires radius 2.
        // Old radius-1 tickets must be removed even while the old holder remains visible.
        for (long pos = 1; pos <= 4; pos++) {
            var info = infos.get(pos);
            var liveTickets = installed.get(pos);
            assertTrue(ImmPtlChunkTickets.reconcileTicketRadius(
                info, 2, 150, liveTickets::add, radius -> assertTrue(liveTickets.remove(radius))));
            assertEquals(Set.of(2), liveTickets, "old ticket must not remain installed");
            assertEquals(2, info.installedTicketRadius);
            assertEquals(150, info.lastTicketAttemptGameTime);
            assertFalse(ImmPtlChunkTickets.hasRequiredTicketLevel(32, 2));
            assertFalse(ImmPtlChunkTickets.missingHolderWaitExpired(info, 349));
            assertTrue(ImmPtlChunkTickets.missingHolderWaitExpired(info, 350));
            assertTrue(ImmPtlChunkTickets.hasRequiredTicketLevel(31, 2));
            assertFalse(ImmPtlChunkTickets.reconcileTicketRadius(
                info, 2, 151, radius -> fail("no redundant add"), radius -> fail("no redundant remove")));
        }
        assertEquals(4, waiting.size(), "migration retains the original pending work");

        // Reversing the mode removes radius 2, and purge can use the recorded radius.
        for (long pos = 1; pos <= 4; pos++) {
            var info = infos.get(pos);
            var liveTickets = installed.get(pos);
            assertTrue(ImmPtlChunkTickets.reconcileTicketRadius(
                info, 1, 400, liveTickets::add, radius -> assertTrue(liveTickets.remove(radius))));
            assertEquals(Set.of(1), liveTickets);
            assertTrue(ImmPtlChunkTickets.hasRequiredTicketLevel(32, 1));
            assertTrue(liveTickets.remove(info.installedTicketRadius),
                "purge must remove the installed ticket radius, not the new global radius");
            assertTrue(liveTickets.isEmpty());
        }
    }

    @Test void disabledOrNeverInstalledTicketsAreNotChangedDuringModeReconciliation() {
        var info = new ImmPtlChunkTickets.ChunkTicketInfo(1, 0);
        assertFalse(ImmPtlChunkTickets.reconcileTicketRadius(
            info, 2, 100, radius -> fail("no ticket exists"), radius -> fail("no ticket exists")));
        assertEquals(0, info.installedTicketRadius);
        assertFalse(info.ticketAdded);
    }


    @Test void alreadyDeliveredRadiusUpgradeRetriesFailedEntityPromotionWithoutResending() {
        var queue = new LongLinkedOpenHashSet();
        var waiting = new LongOpenHashSet();
        var info = new ImmPtlChunkTickets.ChunkTicketInfo(1, 0);
        long pos = 42L;

        // The player already received the radius-1 block-ticking chunk.
        assertTrue(ImmPtlChunkTickets.recordTicketAttempt(queue, waiting, pos, info, true, 1, 100));
        assertTrue(waiting.remove(pos));
        assertFalse(info.retry.scheduled());

        var installed = new HashSet<>(Set.of(1));
        assertTrue(ImmPtlChunkTickets.reconcileTicketRadius(info, 2, 150,
            installed::add, radius -> assertTrue(installed.remove(radius))));
        assertEquals(Set.of(2), installed);
        assertEquals(31, ImmPtlChunkTickets.ticketLevelForRadius(info.installedTicketRadius));

        var upgrades = new Long2ObjectOpenHashMap<ChunkLoadingRecovery.DeliveredEntityPromotion>();
        ImmPtlChunkTickets.trackDeliveredEntityUpgrade(upgrades, pos, 1, 2, waiting.contains(pos));
        assertEquals(1, upgrades.size(), "a delivered chunk must be tracked independently of the send queue");

        // Vanilla 32->31 created the entity future, but the 5x5 neighbor range failed.
        var entityFuture = new AtomicReference<CompletableFuture<ChunkResult<String>>>(
            CompletableFuture.completedFuture(ChunkResult.error("unloaded neighbor range")));
        var prepareCalls = new AtomicInteger();
        Runnable retryPromotion = () -> entityFuture.set(ChunkLoadingRecovery.retry(entityFuture.get(),
            () -> CompletableFuture.completedFuture(
                prepareCalls.incrementAndGet() == 1
                    ? ChunkResult.error("still unavailable") : ChunkResult.of("entity ready"))));

        var monitor = upgrades.get(pos);
        assertFalse(monitor.tick(entityFuture.get(), 150, retryPromotion));
        assertFalse(monitor.tick(entityFuture.get(), 169, retryPromotion));
        assertEquals(0, prepareCalls.get(), "failed promotion must respect the retry cooldown");
        assertFalse(monitor.tick(entityFuture.get(), 170, retryPromotion));
        assertEquals(1, prepareCalls.get());
        assertFalse(monitor.tick(entityFuture.get(), 171, retryPromotion));
        assertFalse(monitor.tick(entityFuture.get(), 190, retryPromotion));
        assertEquals(1, prepareCalls.get(), "second failed promotion still needs its 20-tick cooldown");
        assertFalse(monitor.tick(entityFuture.get(), 191, retryPromotion));
        assertEquals(2, prepareCalls.get(), "failure must retry again at the same ticket level");
        assertTrue(monitor.tick(entityFuture.get(), 192, retryPromotion),
            "successful entity future must complete monitoring");
        upgrades.remove(pos);
        assertTrue(upgrades.isEmpty());
        assertFalse(waiting.contains(pos), "upgrading entities must not reacquire a chunk-send slot");
        assertTrue(queue.isEmpty(), "the delivered chunk must not enter the resend queue");
        assertEquals(2, info.installedTicketRadius);
    }

    @Test void deliveredUpgradeTrackerIgnoresPendingAndDropsDowngradedChunks() {
        var upgrades = new Long2ObjectOpenHashMap<ChunkLoadingRecovery.DeliveredEntityPromotion>();
        ImmPtlChunkTickets.trackDeliveredEntityUpgrade(upgrades, 1, 1, 2, true);
        assertTrue(upgrades.isEmpty(), "pending chunks use the existing throttle retry");
        ImmPtlChunkTickets.trackDeliveredEntityUpgrade(upgrades, 2, 1, 2, false);
        assertEquals(1, upgrades.size());
        var same = upgrades.get(2);
        ImmPtlChunkTickets.trackDeliveredEntityUpgrade(upgrades, 2, 1, 2, false);
        assertSame(same, upgrades.get(2), "duplicate tracking must preserve the retry cooldown");
        ImmPtlChunkTickets.trackDeliveredEntityUpgrade(upgrades, 2, 2, 1, false);
        assertTrue(upgrades.isEmpty(), "downgrades must not retain obsolete entity work");
    }

    @Test void deliveredUpgradePreservesPendingSuccessAndExceptionalEntityFutures() {
        var monitor = new ChunkLoadingRecovery.DeliveredEntityPromotion();
        var pending = new CompletableFuture<ChunkResult<String>>();
        assertFalse(monitor.tick(pending, 100, () -> fail("pending work must not be replaced")));
        assertTrue(monitor.tick(CompletableFuture.completedFuture(ChunkResult.of("ok")), 120,
            () -> fail("successful entity promotion must never restart")));
        var exceptional = CompletableFuture.<ChunkResult<String>>failedFuture(new IllegalStateException("disk error"));
        var error = assertThrows(CompletionException.class,
            () -> monitor.tick(exceptional, 140, () -> fail("exceptional I/O must remain visible")));
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
