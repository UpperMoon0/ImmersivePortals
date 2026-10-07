package qouteall.imm_ptl.core.chunk_loading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
