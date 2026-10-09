package qouteall.imm_ptl.core.ducks;

import net.minecraft.server.level.ChunkMap;
import java.util.concurrent.Executor;

public interface IEChunkHolder {
    void ip_retryFailedFutures(ChunkMap chunkMap, Executor executor);
}
