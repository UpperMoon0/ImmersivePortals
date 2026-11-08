package qouteall.imm_ptl.core.compat.valkyrienskies;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.apigame.world.ServerShipWorldCore;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bridge remote portal watchers from logical world-space ship bounds to
 * VS2's physically stored shipyard chunks. Plot coordinates are never
 * inferred from a vanilla player's world-space location.
 *
 * <p>Each demand is bounded, owned by exactly one remote player and ship,
 * and removed on unwatch/logout/dimension unload. Native VS tracking still
 * owns physical, nearby players; we do not replace its watch/unwatch policy.
 */
public final class VSRemoteShipyardTracking {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_SHIP_CHUNKS = 256;
    private static final int MAX_LOGICAL_SPAN = 24;
    private static final long UPDATE_PERIOD = 10;
    private static final Map<ServerPlayer, Map<Long, List<ChunkLoader>>> TRACKED = new IdentityHashMap<>();
    private static boolean installed;

    private VSRemoteShipyardTracking() {}

    public static void install() {
        if (installed) return;
        installed = true;
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> update(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> {
            clear();
            installed = false;
        });
    }

    private static ServerShipWorldCore getWorld(MinecraftServer server) {
        try {
            Object result = server.getClass().getMethod("getShipObjectWorld").invoke(server);
            return result instanceof ServerShipWorldCore world ? world : null;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static void update(MinecraftServer server) {
        if (!server.isSameThread() || server.overworld().getGameTime() % UPDATE_PERIOD != 0) return;
        ServerShipWorldCore shipWorld = getWorld(server);
        if (shipWorld == null) return;
        Map<Long, LoadedServerShip> live = new HashMap<>();
        for (LoadedServerShip ship : shipWorld.getLoadedShips()) {
            if (ship.getActiveChunksSet().getSize() > 0 && ship.getActiveChunksSet().getSize() <= MAX_SHIP_CHUNKS) {
                live.put(ship.getId(), ship);
            }
        }
        Set<ServerPlayer> players = new HashSet<>(server.getPlayerList().getPlayers());
        for (ServerPlayer trackedPlayer : new ArrayList<>(TRACKED.keySet())) {
            if (!players.contains(trackedPlayer) || trackedPlayer.isRemoved()) {
                release(trackedPlayer);
            }
        }
        for (ServerPlayer player : players) {
            Map<Long, List<ChunkLoader>> owned = TRACKED.computeIfAbsent(player, ignored -> new HashMap<>());
            for (long id : new ArrayList<>(owned.keySet())) {
                LoadedServerShip ship = live.get(id);
                if (ship == null || !isRemoteWatcher(player, ship)) {
                    for (ChunkLoader loader : owned.remove(id)) {
                        ImmPtlChunkTracking.removePerPlayerAdditionalChunkLoader(player, loader);
                    }
                }
            }
            for (LoadedServerShip ship : live.values()) {
                if (owned.containsKey(ship.getId()) || !isRemoteWatcher(player, ship)) continue;
                ResourceKey<Level> dimension = dimensionKey(server, ship.getChunkClaimDimension());
                if (dimension == null) continue;
                List<ChunkLoader> loaders = new ArrayList<>();
                ship.getActiveChunksSet().forEach((chunkX, chunkZ) -> {
                    ChunkLoader loader = new ChunkLoader(dimension, chunkX, chunkZ, 0);
                    loaders.add(loader);
                    ImmPtlChunkTracking.addPerPlayerAdditionalChunkLoader(player, loader);
                });
                if (!loaders.isEmpty()) {
                    owned.put(ship.getId(), loaders);
                    LOGGER.debug("IP/VS bridge registered {} shipyard chunks for remote player {} ship {}",
                        loaders.size(), player.getUUID(), ship.getId());
                }
            }
            if (owned.isEmpty()) TRACKED.remove(player);
        }
    }

    private static ResourceKey<Level> dimensionKey(MinecraftServer server, String dimensionId) {
        for (ResourceKey<Level> dimension : server.levelKeys()) {
            if (dimension.location().toString().equals(dimensionId)) return dimension;
        }
        return null;
    }

    private static boolean isRemoteWatcher(ServerPlayer player, LoadedServerShip ship) {
        if (ship.getWorldAABB() == null) return false;
        AABBdc box = ship.getWorldAABB();
        if (!Double.isFinite(box.minX()) || !Double.isFinite(box.minY())
            || !Double.isFinite(box.minZ()) || !Double.isFinite(box.maxX())
            || !Double.isFinite(box.maxY()) || !Double.isFinite(box.maxZ())) return false;
        MinecraftServer server = player.server;
        ResourceKey<Level> dimension = dimensionKey(server, ship.getChunkClaimDimension());
        if (dimension == null) return false;

        // Vanilla/native VS already handles a player physically close to the
        // ship in its own dimension. It must not receive duplicate IP plots.
        if (player.serverLevel().dimension().equals(dimension) &&
            new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ())
                .inflate(96).contains(player.position())) {
            return false;
        }
        int minX = (int) Math.floor(box.minX()) >> 4;
        int maxX = (int) Math.floor(box.maxX()) >> 4;
        int minZ = (int) Math.floor(box.minZ()) >> 4;
        int maxZ = (int) Math.floor(box.maxZ()) >> 4;
        if (maxX - minX > MAX_LOGICAL_SPAN || maxZ - minZ > MAX_LOGICAL_SPAN) {
            LOGGER.warn("VS remote plot bridge rejected oversized world footprint for ship {}", ship.getId());
            return false;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (ImmPtlChunkTracking.isPlayerWatchingChunk(player, dimension, x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void release(ServerPlayer player) {
        Map<Long, List<ChunkLoader>> owned = TRACKED.remove(player);
        if (owned == null) return;
        for (List<ChunkLoader> loaders : owned.values()) {
            for (ChunkLoader loader : loaders) {
                ImmPtlChunkTracking.removePerPlayerAdditionalChunkLoader(player, loader);
            }
        }
    }

    private static void clear() {
        for (ServerPlayer player : new ArrayList<>(TRACKED.keySet())) release(player);
        TRACKED.clear();
    }
}
