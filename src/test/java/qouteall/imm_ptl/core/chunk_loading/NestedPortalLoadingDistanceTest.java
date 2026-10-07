package qouteall.imm_ptl.core.chunk_loading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NestedPortalLoadingDistanceTest {
    @Test
    void lowViewDistanceStillIncludesTheFarSideOfABoundaryPortal() {
        // Portal destination z=0 is in chunk 0; visible geometry at z=-4 is in -1.
        // The former 3/4==0 radius loaded only the excluded side and rendered End sky.
        assertEquals(1, ChunkVisibility.getNestedPortalLoadingDistance(2));
        assertEquals(1, ChunkVisibility.getNestedPortalLoadingDistance(3));
        assertEquals(1, ChunkVisibility.getNestedPortalLoadingDistance(4));
        assertEquals(2, ChunkVisibility.getNestedPortalLoadingDistance(8));
        assertEquals(8, ChunkVisibility.getNestedPortalLoadingDistance(32));
    }

    @Test
    void everyServerPerformanceBranchRetainsTheTargetAndBoundsActualChunkEnumeration() {
        var center = new DimensionalChunkPos(null, 2, 0);
        for (var performance : PerformanceLevel.values()) {
            var loader = ChunkVisibility.createIndirectChunkLoader(center, 8, performance);
            int expectedRadius = performance == PerformanceLevel.good ? 9 : 2;
            assertEquals(expectedRadius, loader.radius(), performance.toString());
            assertEquals((expectedRadius * 2 + 1) * (expectedRadius * 2 + 1), loader.getChunkNum());
            var chunks = new java.util.HashSet<net.minecraft.world.level.ChunkPos>();
            loader.foreachChunkPos((dimension, x, z, distance) -> {
                assertTrue(distance <= expectedRadius);
                chunks.add(new net.minecraft.world.level.ChunkPos(x, z));
            });
            assertEquals(loader.getChunkNum(), chunks.size());
            // The destination center and z=-1 visible wall retain their full 3x3 neighborhoods.
            for (int x = 1; x <= 3; x++) for (int z = -2; z <= 1; z++) {
                assertTrue(chunks.contains(new net.minecraft.world.level.ChunkPos(x, z)), performance + " " + x + "," + z);
            }
        }
    }

    @Test
    void adaptiveRadiusNeverExpandsExistingCapsAndGoodPerformanceIsUnchanged() {
        var center = new DimensionalChunkPos(null, 0, 0);
        for (int cappedRadius : new int[]{0, 1, 2, 7, 8, 32}) {
            assertEquals(cappedRadius + 1,
                ChunkVisibility.createIndirectChunkLoader(center, cappedRadius, PerformanceLevel.good).radius());
            for (var performance : new PerformanceLevel[]{PerformanceLevel.medium, PerformanceLevel.bad}) {
                assertEquals(Math.min(cappedRadius, 1) + 1,
                    ChunkVisibility.createIndirectChunkLoader(center, cappedRadius, performance).radius());
            }
        }
    }

    @Test
    void meshNeighborHaloIsAddedAfterTheVisibleRadiusCap() {
        assertEquals(1, ChunkVisibility.getChunkDataLoadingRadius(0));
        assertEquals(1, ChunkVisibility.getChunkDataLoadingRadius(-1));
        assertEquals(1, ChunkVisibility.getChunkDataLoadingRadius(Integer.MIN_VALUE));
        assertEquals(2, ChunkVisibility.getChunkDataLoadingRadius(1));
        assertEquals(3, ChunkVisibility.getChunkDataLoadingRadius(2));
        assertEquals(9, ChunkVisibility.getChunkDataLoadingRadius(8));
        assertEquals(65, ChunkVisibility.getChunkDataLoadingRadius(64)); // Global portals may double the configured32.
        assertThrows(ArithmeticException.class, () -> ChunkVisibility.getChunkDataLoadingRadius(Integer.MAX_VALUE));
    }
}
