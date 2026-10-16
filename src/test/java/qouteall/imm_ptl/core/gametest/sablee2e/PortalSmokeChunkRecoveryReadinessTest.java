package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class PortalSmokeChunkRecoveryReadinessTest {
    private enum Result { SUCCESS, ERROR, UNLOADED }

    private static CompletableFuture<Result> completed(Result result) {
        return CompletableFuture.completedFuture(result);
    }

    private static boolean ready(CompletableFuture<Result> ticking, CompletableFuture<Result> entity) {
        return PortalSmokeChunkRecoveryReadiness.ready(ticking, entity, result -> result == Result.SUCCESS);
    }

    @Test
    void pendingEntityPromotionMustCompleteBeforeEitherFailureIsInjected() {
        var ticking = completed(Result.SUCCESS);
        var entity = new CompletableFuture<Result>();
        assertFalse(ready(ticking, entity));
        assertFalse(entity.isDone(), "Readiness must not complete or replace a pending promotion");
        entity.complete(Result.SUCCESS);
        assertTrue(ready(ticking, entity));
    }

    @Test
    void unloadedSentinelAndFailedEntityPromotionAreNotReady() {
        for (Result result : new Result[]{Result.UNLOADED, Result.ERROR}) {
            var entity = completed(result);
            assertFalse(ready(completed(Result.SUCCESS), entity));
            assertSame(result, entity.join(), "Readiness must not repair existing failures");
        }
    }

    @Test
    void tickingPromotionMustAlsoHaveSucceeded() {
        var entity = completed(Result.SUCCESS);
        assertFalse(ready(new CompletableFuture<>(), entity));
        assertFalse(ready(completed(Result.UNLOADED), entity));
        assertFalse(ready(completed(Result.ERROR), entity));
        assertTrue(ready(completed(Result.SUCCESS), entity));
    }

    @Test
    void exceptionalPromotionRemainsVisible() {
        var failed = CompletableFuture.<Result>failedFuture(new IllegalStateException("I/O failure"));
        assertThrows(CompletionException.class, () -> ready(completed(Result.SUCCESS), failed));
    }

    @Test
    void probeGatesBothRealFuturesBeforeMutatingHolderOrClientDelivery() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeChunkRecoveryProbe.java"));
        String injection = source.substring(source.indexOf("private static boolean inject("), source.indexOf("public static void tick("));
        int gate = injection.indexOf("if (holder == null || !PortalSmokeChunkRecoveryReadiness.ready(");
        assertTrue(gate >= 0);
        assertTrue(injection.contains("holder.getTickingChunkFuture(), holder.getEntityTickingChunkFuture(), ChunkResult::isSuccess"));
        assertTrue(injection.contains(") || record == null || !record.isLoadedToPlayer) {"));
        int wait = injection.indexOf("holder = null;", gate);
        int retry = injection.indexOf("return false;", wait);
        int mutation = injection.indexOf("field.set(holder,");
        assertTrue(wait > gate && retry > wait && mutation > retry);
        assertTrue(injection.contains("new String[]{\"tickingChunkFuture\", \"entityTickingChunkFuture\"}"));
        assertTrue(injection.contains("ChunkResult.error(\"Injected unloaded neighbor range\")"));
        assertTrue(injection.indexOf("record.isLoadedToPlayer = false;") > mutation);
        assertTrue(injection.contains("markPendingLoading(record)"));
        assertTrue(injection.contains("new ClientboundForgetLevelChunkPacket(POS)"));
        assertTrue(source.contains("end.getGameTime() - injectedTick > 1200"));
        assertTrue(source.contains("holder.getTickingChunk() != null && holder.getEntityTickingChunkFuture().getNow(null) != null"));
        assertTrue(source.contains("holder.getEntityTickingChunkFuture().getNow(null).isSuccess() && record != null && record.isLoadedToPlayer"));
        assertTrue(source.contains("\"injected_failures\", 2"));
        assertTrue(source.contains("\"pending_chunk_resent\", complete"));
    }
}
