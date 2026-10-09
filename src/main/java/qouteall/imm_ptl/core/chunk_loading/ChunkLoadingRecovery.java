package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.server.level.ChunkResult;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Retry failed promotion results; pending/successful futures and exceptional IO remain untouched. */
public final class ChunkLoadingRecovery {
    private ChunkLoadingRecovery() {}

    public static <T> boolean failed(CompletableFuture<ChunkResult<T>> future) {
        ChunkResult<T> result = future.getNow(null);
        return result != null && !result.isSuccess();
    }

    public static <T> CompletableFuture<ChunkResult<T>> retry(
        CompletableFuture<ChunkResult<T>> future, Supplier<CompletableFuture<ChunkResult<T>>> prepare
    ) {
        return failed(future) ? prepare.get() : future;
    }

    public static final class Retry {
        private boolean scheduled;
        private long notBefore;

        public boolean schedule(long gameTime) {
            if (scheduled) return false;
            scheduled = true;
            notBefore = gameTime + 20;
            return true;
        }

        public boolean ready(long gameTime) { return !scheduled || gameTime >= notBefore; }
        public boolean scheduled() { return scheduled; }
        public void started() { scheduled = false; }
    }
}
