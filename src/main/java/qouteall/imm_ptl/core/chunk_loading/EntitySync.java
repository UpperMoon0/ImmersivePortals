package qouteall.imm_ptl.core.chunk_loading;

import de.nick1st.imm_ptl.events.DimensionEvents;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.compat.sable.SableInterface;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IETrackedEntity;
import qouteall.imm_ptl.core.network.PacketRedirection;

public class EntitySync {
    
    public static void init() {
        NeoForge.EVENT_BUS.addListener(DimensionEvents.BeforeRemovingDimensionEvent.class,
                beforeRemovingDimensionEvent -> EntitySync.forceRemoveDimension(beforeRemovingDimensionEvent.dimension));
    }
    
    /**
     * regarding the players in all dimensions
     */
    public static void update(MinecraftServer server) {
        server.getProfiler().push("ip_entity_tracking_update");
        
        for (ServerLevel world : server.getAllLevels()) {
            if (world.players().isEmpty() && !ImmPtlChunkTracking.isDimensionWatched(world.dimension())) {
                continue;
            }

            PacketRedirection.withForceRedirect(
                world,
                () -> {
                    Int2ObjectMap<ChunkMap.TrackedEntity> entityTrackerMap =
                        ((IEChunkMap) world.getChunkSource().chunkMap).ip_getEntityTrackerMap();
                    
                    for (ChunkMap.TrackedEntity trackedEntity : entityTrackerMap.values()) {
                        IETrackedEntity ieTrackedEntity = (IETrackedEntity) trackedEntity;
                        ieTrackedEntity.ip_updateEntityTrackingStatus();
                    }
                }
            );
        }
        
        server.getProfiler().pop();
    }
    
    public static void tick(MinecraftServer server) {
        server.getProfiler().push("ip_entity_tracking_tick");
        
        for (ServerLevel world : server.getAllLevels()) {
            if (world.players().isEmpty() && !ImmPtlChunkTracking.isDimensionWatched(world.dimension())) {
                continue;
            }

            PacketRedirection.withForceRedirect(
                world,
                () -> {
                    ChunkMap chunkMap = world.getChunkSource().chunkMap;
                    Int2ObjectMap<ChunkMap.TrackedEntity> entityTrackerMap =
                        ((IEChunkMap) chunkMap).ip_getEntityTrackerMap();
                    var distanceManager = chunkMap.getDistanceManager();
                    
                    for (ChunkMap.TrackedEntity trackedEntity : entityTrackerMap.values()) {
                        IETrackedEntity ieTrackedEntity = (IETrackedEntity) trackedEntity;
                        
                        var entity = ieTrackedEntity.ip_getEntity();
                        long chunkPos = SableInterface.invoker.getEntityTrackingChunk(
                            entity.level(), entity.position()
                        ).toLong();
                        if (distanceManager.inEntityTickingRange(chunkPos)) {
                            ieTrackedEntity.ip_sendChanges();
                        }
                    }
                }
            );
        }
        
        server.getProfiler().pop();
    }
    
    private static void forceRemoveDimension(ServerLevel world) {
    
    }
    
}
