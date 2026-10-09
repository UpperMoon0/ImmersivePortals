package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Development-only fault injection: real End holder failure, client unload, real pending resend. */
public final class PortalSmokeChunkRecoveryProbe {
    private static final ChunkPos POS = new ChunkPos(0, -1);
    private static ChunkHolder holder;
    private static long injectedTick;
    private static boolean recovered;
    private static boolean armed;
    private static String request;

    private PortalSmokeChunkRecoveryProbe() {}

    public static boolean begin(String scene, ServerPlayer player) {
        if (!scene.equals("after-reload:nested-background") || PortalSmokeSupport.diagnosticFixture()) return false;
        request = scene;
        armed = true;
        injectedTick = player.server.getLevel(Level.END).getGameTime();
        return true;
    }

    private static boolean inject(ServerPlayer player) throws ReflectiveOperationException {
        ServerLevel end = player.server.getLevel(Level.END);
        holder = ((IEChunkMap) end.getChunkSource().chunkMap).ip_getUpdatingChunkIfPresent(POS.toLong());
        var records = ImmPtlChunkTracking.getWatchRecordForChunk(Level.END, POS.x, POS.z);
        var record = records == null ? null : records.get(player);
        if (holder == null || holder.getTickingChunk() == null || record == null || !record.isLoadedToPlayer) {
            holder = null;
            return false; // Wait for this scene's actual watch and initial delivery.
        }
        for (String name : new String[]{"tickingChunkFuture", "entityTickingChunkFuture"}) {
            var field = ChunkHolder.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(holder, CompletableFuture.completedFuture(ChunkResult.error("Injected unloaded neighbor range")));
        }
        record.isLoadedToPlayer = false;
        ImmPtlChunkTracking.getPlayerInfo(player).markPendingLoading(record);
        PacketRedirection.withForceRedirect(end, () -> player.connection.send(new ClientboundForgetLevelChunkPacket(POS)));
        injectedTick = end.getGameTime();
        save(false);
        return true;
    }

    public static void tick(ServerPlayer player) throws ReflectiveOperationException {
        if (!armed || recovered) return;
        ServerLevel end = player.server.getLevel(Level.END);
        if (holder == null) {
            if (!inject(player) && end.getGameTime() - injectedTick > 1200)
                throw new IllegalStateException("CHUNK_RECOVERY_PROBE: initial End watch/delivery was not ready within 1200 ticks");
            return;
        }
        var records = ImmPtlChunkTracking.getWatchRecordForChunk(Level.END, POS.x, POS.z);
        var record = records == null ? null : records.get(player);
        if (holder.getTickingChunk() != null && holder.getEntityTickingChunkFuture().getNow(null) != null
            && holder.getEntityTickingChunkFuture().getNow(null).isSuccess() && record != null && record.isLoadedToPlayer) {
            recovered = true;
            save(true);
            PortalSmokeSupport.write("scene-ready.txt", request);
        } else if (end.getGameTime() - injectedTick > 1200) {
            throw new IllegalStateException("CHUNK_RECOVERY_PROBE: failed End chunk did not recover and resend within 1200 ticks");
        }
    }

    private static void save(boolean complete) {
        PortalSmokeSupport.write("chunk-recovery-evidence.json", new Gson().toJson(Map.of(
            "scene", "after-reload:nested-background", "dimension", "minecraft:the_end", "chunk", java.util.List.of(POS.x, POS.z),
            "injected_failures", 2, "client_unload_sent", true, "ticking_recovered", complete,
            "entity_ticking_recovered", complete, "pending_chunk_resent", complete)));
    }
}
