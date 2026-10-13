package qouteall.imm_ptl.core.gametest.sablee2e;

/** Pure fixture-relative camera framing; never changes the ship or its rider. */
final class SableShipObservationCamera {
    record Point(double x, double y, double z) {
        Point inPlot(double centerX, double centerY, double centerZ) {
            return new Point(centerX + x, centerY + y, centerZ + z);
        }
    }
    record View(Point eye, Point target) {}

    private SableShipObservationCamera() {}

    static View panel() {
        return new View(new Point(.5, 3.1, .5), new Point(.5, 3.1, 2));
    }

    static View overview(double width, double height, double length) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(length)
            || width <= 0 || height <= 0 || length <= 0) {
            throw new IllegalArgumentException("Invalid ship dimensions for camera framing");
        }
        return new View(
            new Point(.5 + Math.max(12, width * .8), height / 2 + Math.max(8, height * 1.5),
                .5 + Math.max(16, length * .8)),
            new Point(.5, height / 2, .5)
        );
    }
}
