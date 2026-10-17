package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ordinary footprint terrain and hidden plot storage are separate non-loading evidence. */
final class SableStandingBalloonChunks {
    private SableStandingBalloonChunks() {}
    static Map<String, Object> snapshot(Level level, SubLevel body, Vec3 playerPosition) {
        var center = body.getPlot().getCenterBlock();
        var loaded = new HashSet<Long>();
        for (var holder : body.getPlot().getLoadedChunks()) loaded.add(holder.getChunk().getPos().toLong());
        var plotChunks = new LinkedHashMap<String, Boolean>();
        for (int x = (center.getX() - 7) >> 4; x <= (center.getX() + 7) >> 4; x++) {
            for (int z = (center.getZ() - 7) >> 4; z <= (center.getZ() + 7) >> 4; z++) {
                long expected = ChunkPos.asLong(x, z);
                boolean present = loaded.contains(expected);
                if (level.isClientSide) {
                    // IP overrides ClientChunkCache packet insertion: its effective cache owns
                    // real plot chunks without populating Sable's superclass holder inventory.
                    // Probe that cache without creating chunks, rejecting fallback/wrong positions.
                    var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    present = SableStandingBalloonAcceptance.isExactLoadedChunk(
                        chunk != null && !(chunk instanceof EmptyLevelChunk), expected,
                        chunk == null ? 0 : chunk.getPos().toLong());
                }
                plotChunks.put(x + "," + z, present);
            }
        }
        double minX = Double.POSITIVE_INFINITY, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
        for (int x : new int[]{-7, 8}) for (int y : new int[]{0, 18}) for (int z : new int[]{-7, 8}) {
            Vec3 point = body.logicalPose().transformPosition(new Vec3(
                center.getX() + x, center.getY() + y, center.getZ() + z));
            minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
            minZ = Math.min(minZ, point.z); maxZ = Math.max(maxZ, point.z);
        }
        if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minZ) || !Double.isFinite(maxZ)
            || !Double.isFinite(playerPosition.x) || !Double.isFinite(playerPosition.z)
            || maxX - minX > 256 || maxZ - minZ > 256)
            throw new IllegalStateException("Invalid standing balloon/player footprint");
        var worldChunks = new LinkedHashMap<String, Boolean>();
        for (int x = ((int) Math.floor(minX)) >> 4; x <= ((int) Math.ceil(maxX) - 1) >> 4; x++) {
            for (int z = ((int) Math.floor(minZ)) >> 4; z <= ((int) Math.ceil(maxZ) - 1) >> 4; z++) {
                worldChunks.put(x + "," + z, level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null);
            }
        }
        int playerX = ((int) Math.floor(playerPosition.x)) >> 4;
        int playerZ = ((int) Math.floor(playerPosition.z)) >> 4;
        worldChunks.put(playerX + "," + playerZ,
            level.getChunkSource().getChunk(playerX, playerZ, ChunkStatus.FULL, false) != null);
        return Map.of("dimension", level.dimension().location().toString(),
            "world_chunks", worldChunks, "plot_chunks", plotChunks,
            "world_bounds", java.util.List.of(minX, minZ, maxX, maxZ),
            "body_position", body.logicalPose().position().toString(),
            "native_plot_chunks", loaded.stream().map(key -> ChunkPos.getX(key) + "," + ChunkPos.getZ(key)).toList(),
            "world_ready", SableStandingBalloonAcceptance.allPresent(worldChunks),
            "plot_ready", SableStandingBalloonAcceptance.allPresent(plotChunks));
    }
    static boolean ready(Map<String, Object> snapshot) {
        return Boolean.TRUE.equals(snapshot.get("world_ready")) && Boolean.TRUE.equals(snapshot.get("plot_ready"));
    }
}
