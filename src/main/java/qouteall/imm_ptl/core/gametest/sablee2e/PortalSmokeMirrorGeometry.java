package qouteall.imm_ptl.core.gametest.sablee2e;

/** Geometry shared by the mirror fixture and its no-GL regression. */
final class PortalSmokeMirrorGeometry {
    private PortalSmokeMirrorGeometry() {}

    static int excludedWallZ() {
        // Blocks fill [z,z+1]. z=-1 touches the mirror plane at 0 and covers
        // the vanilla mirror aperture, which is deliberately offset to -0.01.
        // Keep this wall between the reflected camera (-4) and the mirror,
        // strictly behind both the clipping plane and either aperture offset.
        return -2;
    }

    static void requireIsolatedObserver(boolean spectator, int playerCount, int mirrorCount, boolean rendersPlayers) {
        if (!spectator || playerCount != 1 || mirrorCount != 1 || rendersPlayers) {
            throw new IllegalStateException("Mirror wall/depth fixture must exclude only its lone spectator observer"
                + " spectator=" + spectator + " players=" + playerCount + " mirrors=" + mirrorCount
                + " mirrorRendersPlayers=" + rendersPlayers);
        }
    }

}
