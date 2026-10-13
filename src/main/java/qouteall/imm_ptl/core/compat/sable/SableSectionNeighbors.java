package qouteall.imm_ptl.core.compat.sable;

import java.util.function.BiPredicate;

/** Pure readiness policy for a finalized sparse ship, never for ordinary terrain. */
public final class SableSectionNeighbors {
    private SableSectionNeighbors() {}

    public static boolean canUseExteriorAir(boolean liveOwner, boolean finalized, boolean ownChunkPresent,
        int x, int z, int minX, int minZ, int maxX, int maxZ, BiPredicate<Integer,Integer> present) {
        if (!liveOwner || !finalized || !ownChunkPresent || minX>maxX || minZ>maxZ
            || x<minX || x>maxX || z<minZ || z>maxZ) return false;
        // A finalize packet completes the sparse payload; it does not promise that
        // every chunk inside the bounds exists. Only exterior absence is known air.
        boolean missingExterior=false;
        for (int[] offset:new int[][] {{-1,0},{1,0},{0,-1},{0,1}}) {
            int nx=x+offset[0],nz=z+offset[1];
            if (present.test(nx,nz)) continue;
            if (nx>=minX && nx<=maxX && nz>=minZ && nz<=maxZ) return false;
            missingExterior=true;
        }
        // This is narrowly an exterior-air exception, not a workaround for a
        // different dispatcher/world mismatch affecting fully interior sections.
        return missingExterior;
    }

}
