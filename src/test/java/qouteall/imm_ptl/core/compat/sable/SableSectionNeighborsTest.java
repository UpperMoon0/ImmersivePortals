package qouteall.imm_ptl.core.compat.sable;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SableSectionNeighborsTest {
    private record Chunk(int x,int z) {}

    @Test void isolatedFinalizedSectionHasKnownAirOnEveryExteriorSide() {
        assertTrue(ready(true,true,true,1280064,1280064,1280064,1280064,1280064,1280064,Set.of()));
    }

    @Test void ordinaryStaleUnfinalizedAndMissingOwnChunksNeverQualify() {
        assertFalse(ready(false,true,true,0,0,0,0,0,0,Set.of()));
        assertFalse(ready(true,false,true,0,0,0,0,0,0,Set.of()));
        assertFalse(ready(true,true,false,0,0,0,0,0,0,Set.of()));
    }

    @Test void everyAbsentInteriorNeighborBlocksTheException() {
        Set<Chunk> all=Set.of(new Chunk(-1,0),new Chunk(1,0),new Chunk(0,-1),new Chunk(0,1));
        assertFalse(ready(true,true,true,0,0,-1,-1,1,1,all), "Fully interior false results are outside this fix");
        for (Chunk missing:all) {
            var present=new HashSet<>(all); present.remove(missing);
            assertFalse(ready(true,true,true,0,0,-1,-1,1,1,present),missing.toString());
        }
    }

    @Test void sparsePayloadFinalizationDoesNotInventInteriorChunks() {
        assertFalse(ready(true,true,true,0,0,-20,-30,20,30,Set.of()));
    }

    @Test void cornerNeedsOnlyItsTwoInteriorNeighbors() {
        assertTrue(ready(true,true,true,-3,-5,-3,-5,4,7,Set.of(new Chunk(-2,-5),new Chunk(-3,-4))));
        assertFalse(ready(true,true,true,-3,-5,-3,-5,4,7,Set.of(new Chunk(-2,-5))));
    }

    @Test void largeHiddenPlotCoordinatesDoNotAliasLocalCoordinates() {
        int x=20481024>>4,z=20481040>>4;
        Set<Chunk> real=Set.of(new Chunk(x-1,z),new Chunk(x+1,z),new Chunk(x,z-1),new Chunk(x,z+1));
        assertFalse(ready(true,true,true,x,z,x-2,z-3,x+2,z+3,real));
        var edge=new HashSet<>(real); edge.remove(new Chunk(x-1,z));
        assertTrue(ready(true,true,true,x,z,x,z-3,x+2,z+3,edge));
        assertFalse(ready(true,true,true,x,z,x-2,z-3,x+2,z+3,
            Set.of(new Chunk(-1,0),new Chunk(1,0),new Chunk(0,-1),new Chunk(0,1))));
    }

    @Test void negativeBoundsAndChunkEdgeAreInclusive() {
        int x=-17>>4,z=-1>>4;
        assertTrue(ready(true,true,true,x,z,-2,-1,-1,-1,Set.of(new Chunk(-1,-1))));
        assertFalse(ready(true,true,true,x,z,-2,-1,-1,-1,Set.of()));
    }

    @Test void growthTurnsFormerExteriorAirIntoRequiredInteriorData() {
        assertTrue(ready(true,true,true,0,0,0,0,0,0,Set.of()));
        assertFalse(ready(true,true,true,0,0,0,0,1,0,Set.of()));
        assertTrue(ready(true,true,true,0,0,0,0,1,0,Set.of(new Chunk(1,0))));
    }

    @Test void shrinkingOrInvalidBoundsCannotKeepAnOldSectionEligible() {
        assertFalse(ready(true,true,true,1,0,0,0,0,0,Set.of()));
        assertFalse(ready(true,true,true,0,0,1,0,0,0,Set.of()));
        assertFalse(ready(true,true,true,0,0,0,1,0,0,Set.of()));
    }

    @Test void exteriorAbsenceIsActuallyCheckedAndRejectedOwnersDoNotRead() {
        AtomicInteger calls=new AtomicInteger();
        assertTrue(SableSectionNeighbors.canUseExteriorAir(true,true,true,4,5,4,5,4,5,
            (x,z)->{ calls.incrementAndGet(); return false; }));
        assertFalse(SableSectionNeighbors.canUseExteriorAir(false,true,true,4,5,0,0,8,9,
            (x,z)->{ calls.incrementAndGet(); return true; }));
        assertEquals(4,calls.get());
    }

    private static boolean ready(boolean live,boolean finalized,boolean self,int x,int z,
        int minX,int minZ,int maxX,int maxZ,Set<Chunk> present) {
        return SableSectionNeighbors.canUseExteriorAir(live,finalized,self,x,z,minX,minZ,maxX,maxZ,
            (nx,nz)->present.contains(new Chunk(nx,nz)));
    }
}
