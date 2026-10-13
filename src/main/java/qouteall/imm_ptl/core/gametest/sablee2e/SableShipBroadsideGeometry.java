package qouteall.imm_ptl.core.gametest.sablee2e;

/** Pure geometry/planning for real sideways flight. Planning never advances the native body. */
final class SableShipBroadsideGeometry {
    static final double SPEED = 24, START_GAP = 20, CLEAR_GAP = 12;
    static final double PORTAL_Y = 64, PORTAL_WIDTH = 160, PORTAL_HEIGHT = 80;
    static final int ASSEMBLY_Y = 48;
    record Point(double x, double y, double z) {}
    /** Coordinates in the entry portal's frame, including after native dimension transfer. */
    record Bounds(double minNormal, double maxNormal, double minWidth, double maxWidth,
                  double minHeight, double maxHeight) {
        double centerNormal() { return (minNormal + maxNormal) / 2; }
        boolean fitsAperture() {
            return minWidth > -PORTAL_WIDTH / 2 + 1 && maxWidth < PORTAL_WIDTH / 2 - 1
                && minHeight > -PORTAL_HEIGHT / 2 + 1 && maxHeight < PORTAL_HEIGHT / 2 - 1;
        }
        boolean leading() { return minNormal <= 0 && maxNormal > 0; }
        boolean center() { return centerNormal() <= 0 && maxNormal > 0; }
        boolean trailing() { return maxNormal <= 0; }
    }
    record Launch(double distance, double duration, double upwardSpeed, double maxRise) {}

    private SableShipBroadsideGeometry() {}

    static Bounds bounds(Point[] portalLocalCorners) {
        if (portalLocalCorners.length != 8) throw new IllegalArgumentException("Eight actual hull corners required");
        double minN=Double.POSITIVE_INFINITY,minW=minN,minH=minN;
        double maxN=Double.NEGATIVE_INFINITY,maxW=maxN,maxH=maxN;
        for (Point p:portalLocalCorners) {
            if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z))
                throw new IllegalArgumentException("Non-finite hull corner");
            minW=Math.min(minW,p.x); maxW=Math.max(maxW,p.x);
            minH=Math.min(minH,p.y); maxH=Math.max(maxH,p.y);
            minN=Math.min(minN,p.z); maxN=Math.max(maxN,p.z);
        }
        return new Bounds(minN,maxN,minW,maxW,minH,maxH);
    }

    /** Constant-gravity, linear-damping estimate; the actual native samples are the oracle. */
    static Launch launch(double distance, double gravityY, double drag) {
        if (!Double.isFinite(distance) || distance<=0 || !Double.isFinite(gravityY) || gravityY>=0
            || !Double.isFinite(drag) || drag<0 || drag*distance>=SPEED)
            throw new IllegalArgumentException("Unsupported native broadside launch parameters");
        double time=drag<1e-8 ? distance/SPEED : -Math.log1p(-drag*distance/SPEED)/drag;
        double upward=drag<1e-8 ? -gravityY*time/2
            : -gravityY*(time-distance/SPEED)/(drag*distance/SPEED);
        double peakTime=drag<1e-8 ? upward/-gravityY : Math.log1p(drag*upward/-gravityY)/drag;
        double rise=verticalDisplacement(upward,gravityY,drag,peakTime);
        return new Launch(distance,time,upward,rise);
    }

    static double verticalDisplacement(double upward, double gravityY, double drag, double time) {
        if (drag<1e-8) return upward*time+gravityY*time*time/2;
        double integral=-Math.expm1(-drag*time)/drag;
        return upward*integral+gravityY/drag*(time-integral);
    }
}
