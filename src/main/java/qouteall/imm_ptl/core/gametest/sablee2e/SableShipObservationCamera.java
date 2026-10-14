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

    /**
     * Keep the overview below the nearby horizontal reverse portal after a crossing.
     * The higher initial view is useful for the deck, but can put the camera through the
     * reverse seam even while the real rider remains safely behind it. The caller also
     * checks the actual world-space camera segment, so unexpected pitch/roll fails closed.
     */
    static View overviewAfterCrossing(double width, double height, double length) {
        View high = overview(width, height, length);
        return new View(new Point(high.eye().x(), height + 2.5, high.eye().z()), high.target());
    }

    /** Outbound broadside travel leaves the reverse vertical seam on the local +X side. */
    static View overviewAfterBroadsideCrossing(double width, double height, double length) {
        View view = overviewAfterCrossing(width, height, length);
        return new View(new Point(2 * view.target().x() - view.eye().x(), view.eye().y(), view.eye().z()), view.target());
    }

    /** A previous or cross-portal-remapped camera cannot certify this requested view. */
    static boolean matchesObservedView(View requested, View observed) {
        double dx = requested.eye().x() - observed.eye().x();
        double dy = requested.eye().y() - observed.eye().y();
        double dz = requested.eye().z() - observed.eye().z();
        if (!(dx * dx + dy * dy + dz * dz <= .25 * .25)) return false;
        double ax = requested.target().x() - requested.eye().x();
        double ay = requested.target().y() - requested.eye().y();
        double az = requested.target().z() - requested.eye().z();
        double bx = observed.target().x() - observed.eye().x();
        double by = observed.target().y() - observed.eye().y();
        double bz = observed.target().z() - observed.eye().z();
        double lengths = Math.sqrt((ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz));
        return lengths > 0 && (ax * bx + ay * by + az * bz) / lengths >= .999;
    }
}
