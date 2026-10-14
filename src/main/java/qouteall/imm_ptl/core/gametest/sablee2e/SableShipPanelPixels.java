package qouteall.imm_ptl.core.gametest.sablee2e;

/** A three-color target rejects bare Nether background as well as stale opposite-color panel meshes. */
public final class SableShipPanelPixels {
    private SableShipPanelPixels() {}
    public static boolean accepts(int desired, int opposite, int blue, int yellow, int area) {
        return area > 0 && desired >= 1000 && (long) desired * 5 >= area
            && opposite >= 0 && (long) opposite * 20 <= desired
            && blue >= 500 && (long) blue * 20 >= area
            && yellow >= 500 && (long) yellow * 20 >= area
            && (long) desired + opposite + blue + yellow <= area;
    }
}
