package qouteall.imm_ptl.core.chunk_loading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
