package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class ChunkVisibility {
    private static final LimitedLogger limitedLogger = new LimitedLogger(10);
    
    private static final int portalLoadingRange = 48;
    public static final int secondaryPortalLoadingRange = 16;
    
    public static ChunkLoader playerDirectLoader(ServerPlayer player) {
        return new ChunkLoader(
            new DimensionalChunkPos(
                player.level().dimension(),
                player.chunkPosition()
            ),
            getChunkDataLoadingRadius(McHelper.getPlayerLoadDistance(player))
        );
    }
    
    private static int getDirectLoadingDistance(int renderDistance, double distanceToPortal) {
        if (distanceToPortal < 5) {
            return renderDistance;
        }
        if (distanceToPortal < 15) {
            return (renderDistance * 2) / 3;
        }
        return renderDistance / 3;
    }
    
    private static int getCappedLoadingDistance(
        Portal portal, ServerPlayer player, int targetLoadingDistance
    ) {
        PerformanceLevel performanceLevel =
            ImmPtlChunkTracking.getPlayerInfo(player).performanceLevel;
        int cap1 = PerformanceLevel.getIndirectLoadingRadiusCap(performanceLevel);
        int cap2 = IPGlobal.indirectLoadingRadiusCap;
        int cap3 = PerformanceLevel.getIndirectLoadingRadiusCap(ServerPerformanceMonitor.getLevel());
        
        int cap = Math.min(cap1, cap2);
        
        // load more for scaling portal
        if (portal.getScale() > 2) {
            cap *= 2;
        }
        
        int cappedLoadingDistance = Math.min(targetLoadingDistance, cap);
        
        return cappedLoadingDistance;
    }
    
    public static List<Portal> getNearbyPortals(
        ServerLevel world, Vec3 pos, Predicate<Portal> predicate,
        int radiusChunks, int radiusChunksForGlobalPortals
    ) {
        List<Portal> result = McHelper.findEntitiesRough(
            Portal.class,
            world,
            pos,
            radiusChunks,
            predicate
        );
        
        for (Portal globalPortal : GlobalPortalStorage.getGlobalPortals(world)) {
            double distance = globalPortal.getDistanceToNearestPointInPortal(pos);
            if (distance < radiusChunksForGlobalPortals * 16) {
                result.add(globalPortal);
            }
        }
        
        if (result.size() > 100) {
            limitedLogger.err("too many portal nearby " + world + pos);
            
            Optional<Portal> nearest =
                result.stream().min(Comparator.comparingDouble(p -> p.getDistanceToNearestPointInPortal(pos)));
            
            return List.of(nearest.get());
        }
        
        return result;
    }
    
    private static ChunkLoader getGeneralDirectPortalLoader(
        ServerPlayer player, Portal portal
    ) {
        if (portal.getIsGlobal()) {
            int renderDistance = Math.min(
                IPGlobal.indirectLoadingRadiusCap * 2,
                //load a little more to make dimension stack more complete
                Math.max(
                    2,
                    McHelper.getPlayerLoadDistance(player) -
                        Math.floorDiv((int) portal.getDistanceToNearestPointInPortal(player.position()), 16)
                )
            );
            
            return new ChunkLoader(
                new DimensionalChunkPos(
                    portal.getDestDim(),
                    new ChunkPos(BlockPos.containing(
                        portal.transformPoint(player.position())
                    ))
                ),
                getChunkDataLoadingRadius(renderDistance)
            );
        }
        else {
            int loadDistance = McHelper.getPlayerLoadDistance(player);
            double distance = portal.getDistanceToNearestPointInPortal(player.position());
            
            // load more for up scaling portal
            if (portal.getScaling() > 2 && distance < 5) {
                loadDistance = (int) ((portal.getDestAreaRadiusEstimation() * 1.4) / 16);
            }
            
            return new ChunkLoader(
                new DimensionalChunkPos(
                    portal.getDestDim(),
                    new ChunkPos(BlockPos.containing(portal.getDestPos()))
                ),
                getChunkDataLoadingRadius(getCappedLoadingDistance(
                    portal, player,
                    getDirectLoadingDistance(loadDistance, distance)
                ))
            );
        }
    }
    
    private static ChunkLoader getGeneralPortalIndirectLoader(
        ServerPlayer player,
        Vec3 transformedPos,
        Portal portal
    ) {
        int loadDistance = McHelper.getPlayerLoadDistance(player);
        
        if (portal.getIsGlobal()) {
            int renderDistance = Math.min(
                IPGlobal.indirectLoadingRadiusCap,
                loadDistance / 3
            );
            return createIndirectChunkLoader(
                new DimensionalChunkPos(
                    portal.getDestDim(),
                    new ChunkPos(BlockPos.containing(transformedPos))
                ),
                Math.max(1, renderDistance), ServerPerformanceMonitor.getLevel()
            );
        }
        else {
            return createIndirectChunkLoader(
                new DimensionalChunkPos(
                    portal.getDestDim(),
                    new ChunkPos(BlockPos.containing(portal.getDestPos()))
                ),
                getCappedLoadingDistance(
                    portal, player, getNestedPortalLoadingDistance(loadDistance)
                ), ServerPerformanceMonitor.getLevel()
            );
        }
    }

    /**
     * Reduce work when the server is busy without dropping a visible second-hop
     * destination. The one-chunk visible radius covers both sides of a boundary
     * portal; the outer data ring still supplies every mesh's 3x3 prerequisites.
     * Apply this after the existing caps, so an explicit smaller cap is preserved.
     */
    static ChunkLoader createIndirectChunkLoader(
        DimensionalChunkPos center, int cappedVisibleRadius, PerformanceLevel serverPerformance
    ) {
        int visibleRadius = serverPerformance == PerformanceLevel.good
            ? cappedVisibleRadius : Math.min(cappedVisibleRadius, 1);
        return new ChunkLoader(center, getChunkDataLoadingRadius(visibleRadius));
    }

    /** A portal on a chunk boundary needs its adjacent destination chunks even at low view distances. */
    static int getNestedPortalLoadingDistance(int playerLoadDistance) {
        return Math.max(1, playerLoadDistance / 4);
    }

    /**
     * Sodium and Embeddium build a chunk only after its entire 3x3 neighborhood has
     * block and light data. Every visible radius must therefore have one data ring
     * around it. Add that ring after capping: a visible radius of one at z=0
     * includes chunk z=-1, whose mesh needs z=-2 even though that chunk is not drawn.
     * This also restores vanilla's outer data border for the main view: CE replaces
     * both ChunkMap.applyChunkTrackingView and PlayerChunkSender, so vanilla cannot
     * deliver those neighbors independently. A zero radius still means a center chunk;
     * its data prerequisites occupy radius one. Config caps apply to visible chunks.
     */
    static int getChunkDataLoadingRadius(int cappedVisibleRadius) {
        return Math.addExact(Math.max(0, cappedVisibleRadius), 1);
    }
    
    //includes:
    //1.player direct loader
    //2.loaders from the portals that are directly visible
    //3.loaders from the portals that are indirectly visible through portals
    public static void foreachBaseChunkLoaders(
        ServerPlayer player, Consumer<ChunkLoader> func
    ) {
        PerformanceLevel perfLevel = ImmPtlChunkTracking.getPlayerInfo(player).performanceLevel;
        int visiblePortalRangeChunks = PerformanceLevel.getVisiblePortalRangeChunks(perfLevel);
        int indirectVisiblePortalRangeChunks = PerformanceLevel.getIndirectVisiblePortalRangeChunks(perfLevel);
        
        ChunkLoader playerDirectLoader = playerDirectLoader(player);
    
        func.accept(playerDirectLoader);
    
        List<Portal> nearbyPortals = getNearbyPortals(
            ((ServerLevel) player.level()),
            player.position(),
            portal -> portal.broadcastToPlayer(player),
            visiblePortalRangeChunks, 256
        );
        
        for (Portal portal : nearbyPortals) {
            Level destinationWorld = portal.getDestinationWorld();
    
            if (destinationWorld == null) {
                continue;
            }
    
            Vec3 transformedPlayerPos = portal.transformPoint(player.position());
            
            func.accept(getGeneralDirectPortalLoader(player, portal));
    
            // Keep the existing portal-query bounds at every performance level.
            // Degrading performance shrinks each loader, not the set of visible worlds.
            List<Portal> indirectNearbyPortals = getNearbyPortals(
                ((ServerLevel) destinationWorld),
                transformedPlayerPos,
                p -> p.broadcastToPlayer(player),
                indirectVisiblePortalRangeChunks, 32
            );

            for (Portal innerPortal : indirectNearbyPortals) {
                func.accept(getGeneralPortalIndirectLoader(
                    player, transformedPlayerPos, innerPortal
                ));
            }
        }
    }
    
    public static boolean isShrinkLoading() {
        return ServerPerformanceMonitor.getLevel() != PerformanceLevel.good;
    }
    
}
