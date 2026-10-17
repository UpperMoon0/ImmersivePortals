package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.LinkedHashMap;
import java.util.Map;

/** Non-creating terrain inventory before assembly, across the entire prepared corridor. */
final class SableStandingBalloonColdChunks {
    private SableStandingBalloonColdChunks() {}
    static Map<String, Boolean> snapshot(Level existingLevel, int remoteX, int remoteZ) {
        var chunks = new LinkedHashMap<String, Boolean>();
        for (int x = (remoteX - 12) >> 4; x <= (remoteX + 12) >> 4; x++) {
            for (int z = (remoteZ - 12) >> 4; z <= (remoteZ + 12) >> 4; z++) {
                chunks.put(x + "," + z, existingLevel != null
                    && existingLevel.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null);
            }
        }
        return chunks;
    }
}
