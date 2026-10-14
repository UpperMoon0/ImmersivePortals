package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import qouteall.imm_ptl.core.gametest.sablee2e.SableShipObservationCamera.Point;
import qouteall.imm_ptl.core.gametest.sablee2e.SableShipObservationCamera.View;

/** Dependency-free selection of occupied sections that can contribute static ship geometry. */
final class SableShipVisibleSections {
    record Section(int x, int y, int z) {}

    private static final double EPSILON = 1.0e-9;

    private SableShipVisibleSections() {}

    static Set<Section> expectedSections(
        List<Point> occupiedBlockPositions, View view,
        double verticalFovDegrees, double aspectRatio, double maxViewDistanceBlocks
    ) {
        return expectedSections(occupiedBlockPositions, view, new Point(0, 1, 0),
            verticalFovDegrees, aspectRatio, maxViewDistanceBlocks);
    }

    /**
     * Positions are integer block lower corners, already filtered to static model geometry.
     * All positions and camera points must share the same frame (normally the absolute hidden
     * plot). The up argument is a direction in that frame, allowing a rolled/inverse-pose camera.
     *
     * A section is selected only if one supplied block's unit AABB is not wholly outside any
     * camera-frustum plane and intersects the camera's distance sphere. Testing occupied block
     * bounds, rather than whole section bounds, avoids requiring empty/out-of-view geometry.
     * This is deliberately conservative at intersecting planes and does not test occlusion.
     * There is no positive near-clip distance: blocks crossing the eye plane remain candidates.
     */
    static Set<Section> expectedSections(
        List<Point> occupiedBlockPositions, View view, Point upDirection,
        double verticalFovDegrees, double aspectRatio, double maxViewDistanceBlocks
    ) {
        Objects.requireNonNull(occupiedBlockPositions, "occupiedBlockPositions");
        Objects.requireNonNull(view, "view");
        requireFinite(view.eye());
        requireFinite(view.target());
        requireFinite(upDirection);
        if (!Double.isFinite(verticalFovDegrees) || verticalFovDegrees <= 0 || verticalFovDegrees >= 180
            || !Double.isFinite(aspectRatio) || aspectRatio <= 0
            || !Double.isFinite(maxViewDistanceBlocks) || maxViewDistanceBlocks <= 0) {
            throw new IllegalArgumentException("Invalid camera projection or view distance");
        }

        Vector forward = Vector.between(view.eye(), view.target()).normalized();
        Vector upHint = new Vector(upDirection.x(), upDirection.y(), upDirection.z()).normalized();
        Vector right = upHint.cross(forward);
        if (right.length() < EPSILON) {
            // Looking directly along the up hint has no unique roll; choose a stable basis.
            upHint = Math.abs(forward.y()) < .9 ? new Vector(0, 1, 0) : new Vector(0, 0, 1);
            right = upHint.cross(forward);
        }
        right = right.normalized();
        Vector up = forward.cross(right).normalized();
        double verticalTangent = Math.tan(Math.toRadians(verticalFovDegrees / 2));
        double horizontalTangent = verticalTangent * aspectRatio;
        if (!Double.isFinite(horizontalTangent)) {
            throw new IllegalArgumentException("Invalid horizontal field of view");
        }
        Vector[] planes = {
            forward,
            forward.scale(horizontalTangent).plus(right),
            forward.scale(horizontalTangent).plus(right.scale(-1)),
            forward.scale(verticalTangent).plus(up),
            forward.scale(verticalTangent).plus(up.scale(-1))
        };

        Set<Section> result = new LinkedHashSet<>();
        for (Point block : occupiedBlockPositions) {
            requireBlockPosition(block);
            Vector relativeCenter = new Vector(
                block.x() + .5 - view.eye().x(),
                block.y() + .5 - view.eye().y(),
                block.z() + .5 - view.eye().z()
            );
            double distanceToBox = Math.hypot(Math.hypot(
                Math.max(0, Math.abs(relativeCenter.x()) - .5),
                Math.max(0, Math.abs(relativeCenter.y()) - .5)),
                Math.max(0, Math.abs(relativeCenter.z()) - .5));
            if (distanceToBox > maxViewDistanceBlocks + EPSILON) continue;

            boolean intersects = true;
            for (Vector plane : planes) {
                // Maximum signed plane distance among all eight AABB corners.
                double support = .5 * (Math.abs(plane.x()) + Math.abs(plane.y()) + Math.abs(plane.z()));
                if (plane.dot(relativeCenter) + support < -EPSILON) {
                    intersects = false;
                    break;
                }
            }
            if (intersects) {
                result.add(new Section(
                    Math.floorDiv((int) block.x(), 16),
                    Math.floorDiv((int) block.y(), 16),
                    Math.floorDiv((int) block.z(), 16)
                ));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static void requireFinite(Point point) {
        Objects.requireNonNull(point, "point");
        if (!Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())) {
            throw new IllegalArgumentException("Camera and block coordinates must be finite");
        }
    }

    private static void requireBlockPosition(Point point) {
        requireFinite(point);
        for (double coordinate : new double[] {point.x(), point.y(), point.z()}) {
            if (coordinate < Integer.MIN_VALUE || coordinate > Integer.MAX_VALUE || coordinate != Math.rint(coordinate)) {
                throw new IllegalArgumentException("Expected integer block lower-corner coordinates");
            }
        }
    }

    private record Vector(double x, double y, double z) {
        static Vector between(Point from, Point to) {
            return new Vector(to.x() - from.x(), to.y() - from.y(), to.z() - from.z());
        }
        double length() { return Math.hypot(Math.hypot(x, y), z); }
        Vector normalized() {
            double length = length();
            if (!Double.isFinite(length) || length < EPSILON) {
                throw new IllegalArgumentException("Camera directions must be finite and nonzero");
            }
            return scale(1 / length);
        }
        Vector scale(double amount) { return new Vector(x * amount, y * amount, z * amount); }
        Vector plus(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        double dot(Vector other) { return x * other.x + y * other.y + z * other.z; }
        Vector cross(Vector other) {
            return new Vector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
        }
    }
}
