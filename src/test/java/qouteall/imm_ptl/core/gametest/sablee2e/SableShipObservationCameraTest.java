package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableShipObservationCameraTest {
    @Test void everyCameraPointUsesTheAbsoluteHiddenPlotFrame() {
        for (double center : new double[] {-1_900_000_000, -20_481_032, 20_481_032, 1_900_000_000}) {
            for (var view : new SableShipObservationCamera.View[] {
                SableShipObservationCamera.panel(), SableShipObservationCamera.overview(7, 5, 11),
                SableShipObservationCamera.overview(49, 17, 65),
                SableShipObservationCamera.overviewAfterCrossing(7, 5, 11),
                SableShipObservationCamera.overviewAfterCrossing(49, 17, 65)
            }) {
                for (var relative : new SableShipObservationCamera.Point[] {view.eye(), view.target()}) {
                    var absolute = relative.inPlot(center, 128, -center);
                    assertEquals(center + relative.x(), absolute.x());
                    assertEquals(128 + relative.y(), absolute.y());
                    assertEquals(-center + relative.z(), absolute.z());
                }
            }
        }
    }

    @Test void overviewIncludesEveryHullCornerForBothFixtureSizes() {
        double verticalTangent = Math.tan(Math.toRadians(35));
        double horizontalTangent = verticalTangent * 854 / 480;
        for (double[] size : new double[][] {{7, 5, 11}, {49, 17, 65}}) {
            for (var view : new SableShipObservationCamera.View[] {
                SableShipObservationCamera.overview(size[0], size[1], size[2]),
                SableShipObservationCamera.overviewAfterCrossing(size[0], size[1], size[2])
            }) {
                var eye = view.eye();
                double[] forward = normalized(new double[] {view.target().x()-eye.x(), view.target().y()-eye.y(), view.target().z()-eye.z()});
                double[] right = normalized(new double[] {forward[2], 0, -forward[0]});
                double[] up = cross(forward, right);
                for (double x : new double[] {.5-size[0]/2, .5+size[0]/2}) {
                    for (double y : new double[] {0, size[1]}) {
                        for (double z : new double[] {.5-size[2]/2, .5+size[2]/2}) {
                            double[] point = {x-eye.x(), y-eye.y(), z-eye.z()};
                            double depth = dot(point, forward);
                            assertTrue(depth > 0, "Hull must be in front of camera");
                            assertTrue(Math.abs(dot(point, right)) < depth * horizontalTangent, "Hull clips horizontal frame");
                            assertTrue(Math.abs(dot(point, up)) < depth * verticalTangent, "Hull clips vertical frame");
                        }
                    }
                }
            }
        }
    }

    @Test void panelFramingKeepsAllThreeRealColumnsInsideThePixelOracleRegion() {
        var view = SableShipObservationCamera.panel();
        assertEquals(.5, view.eye().x());
        assertEquals(view.eye().x(), view.target().x());
        assertEquals(view.eye().y(), view.target().y());
        assertTrue(view.eye().y() > 2 && view.eye().y() < 4, "View is below the control ship roof");
        double distance = view.target().z() - view.eye().z();
        assertTrue(distance > 0);
        double roiHalfHeight = distance * Math.tan(Math.toRadians(35)) / 2;
        double roiHalfWidth = roiHalfHeight * 854 / 480;
        assertTrue(view.eye().y() - roiHalfHeight >= 2 && view.eye().y() + roiHalfHeight <= 5);
        // Each side color occupies the ROI outside the one-block center column.
        double centerFraction = 1 / (2 * roiHalfWidth);
        double sideFraction = (1 - centerFraction) / 2;
        assertTrue(centerFraction >= .2, "Real update column must fill at least 20% of the ROI");
        assertTrue(sideFraction >= .05, "Both fixed identity columns must fill at least 5% of the ROI");
    }

    @Test void destinationOverviewStaysBelowTheActualReverseSeamThatRemappedTheOldView() {
        // Head 054 LARGE ordinary destination receipt: yaw-only body anchor, rider and reverse plane.
        double bodyAnchorY = 190.320331, riderY = 192.752831, reversePlaneY = 224;
        var high = SableShipObservationCamera.overview(49, 17, 65);
        var low = SableShipObservationCamera.overviewAfterCrossing(49, 17, 65);
        assertTrue(riderY < reversePlaneY);
        assertTrue(bodyAnchorY + high.eye().y() > reversePlaneY,
            "The previous camera segment crossed the portal while its real rider did not");
        assertTrue(bodyAnchorY + low.eye().y() < reversePlaneY - 14,
            "The lower full-hull view stays comfortably on the rider's side");
        assertEquals(high.eye().x(), low.eye().x());
        assertEquals(high.eye().z(), low.eye().z());
        assertEquals(high.target(), low.target());
    }

    @Test void onlyTheRequestedRenderedCameraCanCertifyAnOverview() {
        var view = SableShipObservationCamera.overviewAfterCrossing(49, 17, 65);
        assertTrue(SableShipObservationCamera.matchesObservedView(view, view));
        var eye = view.eye();
        var target = view.target();
        assertTrue(SableShipObservationCamera.matchesObservedView(view, new SableShipObservationCamera.View(
            new SableShipObservationCamera.Point(eye.x() + .1, eye.y(), eye.z()),
            new SableShipObservationCamera.Point(target.x() + .1, target.y(), target.z()))));
        assertFalse(SableShipObservationCamera.matchesObservedView(view, new SableShipObservationCamera.View(
            new SableShipObservationCamera.Point(eye.x() + 1, eye.y(), eye.z()), target)));
        assertFalse(SableShipObservationCamera.matchesObservedView(view, new SableShipObservationCamera.View(
            eye, new SableShipObservationCamera.Point(2 * eye.x() - target.x(), 2 * eye.y() - target.y(), 2 * eye.z() - target.z()))));
        assertFalse(SableShipObservationCamera.matchesObservedView(view, new SableShipObservationCamera.View(eye, eye)));
        // Source-world camera position must never be mistaken for destination-body plot coordinates.
        var remapped = new SableShipObservationCamera.Point(-68.303590, -70, 44.966306);
        assertFalse(SableShipObservationCamera.matchesObservedView(view, new SableShipObservationCamera.View(remapped, target)));
    }

    @Test void invalidHullDimensionsCannotProduceAMisleadingOverview() {
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> SableShipObservationCamera.overview(invalid, 5, 11));
            assertThrows(IllegalArgumentException.class, () -> SableShipObservationCamera.overview(7, invalid, 11));
            assertThrows(IllegalArgumentException.class, () -> SableShipObservationCamera.overview(7, 5, invalid));
            assertThrows(IllegalArgumentException.class, () -> SableShipObservationCamera.overviewAfterCrossing(invalid, 5, 11));
            assertThrows(IllegalArgumentException.class, () -> SableShipObservationCamera.overviewAfterCrossing(7, invalid, 11));
            assertThrows(IllegalArgumentException.class, () -> SableShipObservationCamera.overviewAfterCrossing(7, 5, invalid));
        }
    }

    private static double[] normalized(double[] vector) {
        double length = Math.sqrt(dot(vector, vector));
        return new double[] {vector[0]/length, vector[1]/length, vector[2]/length};
    }
    private static double dot(double[] a, double[] b) { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]; }
    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]};
    }
}
