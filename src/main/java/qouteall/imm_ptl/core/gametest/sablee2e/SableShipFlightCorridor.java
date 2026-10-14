package qouteall.imm_ptl.core.gametest.sablee2e;

/** Disposable powered-test terrain envelope and bounded deterministic traversal. */
final class SableShipFlightCorridor {
    static final int MIN_XZ = -128, MAX_XZ = 128, MIN_Y = 0, MAX_Y = 96;
    static final int POSITIONS_PER_TICK = 32_768;
    static final long SOFT_NANOS_PER_TICK = 50_000_000L;
    static final int WIDTH = MAX_XZ - MIN_XZ + 1, HEIGHT = MAX_Y - MIN_Y + 1;
    static final int VOLUME = WIDTH * WIDTH * HEIGHT;

    private SableShipFlightCorridor() {}
    static int x(int index) { check(index); return MIN_XZ + index / (WIDTH * HEIGHT); }
    static int y(int index) { check(index); return MIN_Y + index % HEIGHT; }
    static int z(int index) { check(index); return MIN_XZ + index / HEIGHT % WIDTH; }
    static boolean contains(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
            && x >= MIN_XZ && x <= MAX_XZ + 1 && y >= MIN_Y && y <= MAX_Y + 1
            && z >= MIN_XZ && z <= MAX_XZ + 1;
    }
    private static void check(int index) {
        if (index < 0 || index >= VOLUME) throw new IndexOutOfBoundsException(index);
    }
}
