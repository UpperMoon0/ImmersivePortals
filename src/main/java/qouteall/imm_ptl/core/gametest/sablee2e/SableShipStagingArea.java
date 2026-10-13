package qouteall.imm_ptl.core.gametest.sablee2e;

/** Real support for the unmounted test player between flights, outside every swept hull. */
final class SableShipStagingArea {
    static final int X=112, Y=199, Z=112, RADIUS=2;
    static final double PLAYER_X=X+.5, PLAYER_Y=Y+1, PLAYER_Z=Z+.5;
    private SableShipStagingArea() {}

    static boolean supportedPosition(double x,double y,double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && x>=X-RADIUS+.3 && x<=X+RADIUS+1-.3
            && z>=Z-RADIUS+.3 && z<=Z+RADIUS+1-.3
            && Math.abs(y-PLAYER_Y)<.05;
    }
}
