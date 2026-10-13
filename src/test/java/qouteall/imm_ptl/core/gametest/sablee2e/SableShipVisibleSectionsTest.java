package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import qouteall.imm_ptl.core.gametest.sablee2e.SableShipObservationCamera.Point;
import qouteall.imm_ptl.core.gametest.sablee2e.SableShipObservationCamera.View;
import qouteall.imm_ptl.core.gametest.sablee2e.SableShipVisibleSections.Section;

import static org.junit.jupiter.api.Assertions.*;

class SableShipVisibleSectionsTest {
    private static final double FOV = 70;
    private static final double ASPECT = 854.0 / 480;
    private static final View FORWARD = new View(new Point(.5, .5, .5), new Point(.5, .5, 10));

    @Test void emptyManifestCannotDemandEmptySections() {
        assertEquals(Set.of(), visible(List.of(), FORWARD, 128));
    }

    @Test void deduplicatesOccupiedSectionsWithoutFillingGapsBetweenThem() {
        assertEquals(Set.of(new Section(0, 0, 0), new Section(0, 0, 2)), visible(List.of(
            new Point(0, 0, 10), new Point(0, 0, 11), new Point(0, 0, 10), new Point(0, 0, 40)
        ), FORWARD, 128));
    }

    @Test void selectsActualOccupiedBlocksRatherThanAnIntersectingSectionBox() {
        // Section (0,0,0) intersects the camera, but its only supplied block is outside the view.
        assertEquals(Set.of(), visible(List.of(new Point(15, 15, 0)), FORWARD, 128));
    }

    @Test void rejectsBlocksBehindBesideAboveBelowAndBeyondTheCamera() {
        assertEquals(Set.of(new Section(0, 0, 1)), visible(List.of(
            new Point(0, 0, 20), new Point(0, 0, -20), new Point(50, 0, 20),
            new Point(-50, 0, 20), new Point(0, 30, 20), new Point(0, -30, 20),
            new Point(0, 0, 200)
        ), FORWARD, 100));
    }

    @Test void usesNearestBoxDistanceRatherThanOnlyItsCenterOrForwardDepth() {
        View view = new View(new Point(0, .5, 0), new Point(0, .5, 1));
        assertEquals(Set.of(new Section(0, 0, 1)), visible(List.of(new Point(0, 0, 16)), view, 16));
        assertEquals(Set.of(), visible(List.of(new Point(12, 0, 16)), view, 19));
        assertEquals(Set.of(), visible(List.of(new Point(0, 0, 17)), view, 16));
    }

    @Test void keepsFrustumEdgeIntersectionsEvenWhenTheBlockCenterIsOutside() {
        View view = new View(new Point(0, 0, 0), new Point(0, 0, 1));
        // At 90 degrees each side plane is x=+/-z or y=+/-z.
        assertEquals(Set.of(new Section(1, 0, 0), new Section(0, 1, 0)),
            SableShipVisibleSections.expectedSections(List.of(
                new Point(16, 0, 15), new Point(0, 16, 15)
            ), view, 90, 1, 128));
        assertEquals(Set.of(), SableShipVisibleSections.expectedSections(
            List.of(new Point(17, 0, 15)), view, 90, 1, 128));
    }

    @Test void keepsEyePlaneCrossingAndFrustumThroughBlockWithNoCornerInside() {
        assertEquals(Set.of(new Section(0, 0, 0)), visible(List.of(new Point(0, 0, 0)), FORWARD, 128));
        View narrow = new View(new Point(.5, .5, -1), new Point(.5, .5, 1));
        // All eight corners lie outside this narrow frustum, but it passes through the block.
        assertEquals(Set.of(new Section(0, 0, 0)), SableShipVisibleSections.expectedSections(
            List.of(new Point(0, 0, 0)), narrow, 1, 1, 128));
    }

    @Test void negativeBlockCoordinatesUseFloorDivisionOnAllThreeAxes() {
        List<Point> blocks = List.of(new Point(-1, -1, -1), new Point(-16, -16, -16), new Point(-17, -17, -17));
        View view = new View(new Point(-40, -40, -40), new Point(-8, -8, -8));
        assertEquals(Set.of(new Section(-1, -1, -1), new Section(-2, -2, -2)), visible(blocks, view, 128));
    }

    @Test void completeOverviewSelectsEveryOccupiedSectionOfBothRealFixtureSizes() {
        for (boolean large : new boolean[] {false, true}) {
            var layout = SableFlightShipLayout.create(large);
            var relative = layout.blocks().keySet().stream().map(p -> new Point(p.x(), p.y(), p.z())).toList();
            for (var view : new View[] {
                SableShipObservationCamera.overview(layout.width(), layout.height(), layout.length()),
                SableShipObservationCamera.overviewAfterCrossing(layout.width(), layout.height(), layout.length())
            }) {
                for (int offset : new int[] {20_481_024, 20_481_032, -20_481_024, -20_481_032, 1_900_000_000, -1_900_000_000}) {
                    var blocks = relative.stream().map(p -> p.inPlot(offset, 128, -offset)).toList();
                    var absoluteView = new View(view.eye().inPlot(offset, 128, -offset), view.target().inPlot(offset, 128, -offset));
                    var occupied = blocks.stream().map(SableShipVisibleSectionsTest::section).collect(Collectors.toSet());
                    assertEquals(occupied, visible(blocks, absoluteView, 160), "Incomplete overview at " + offset);
                    if (large) assertEquals(40, occupied.size(), "Large hull must exercise all 40 occupied render sections");
                }
            }
        }
    }

    @Test void crossPortalRemappedEyeCannotSupplyAFullHullOverview() {
        var layout = SableFlightShipLayout.create(true);
        var blocks = layout.blocks().keySet().stream().map(p -> new Point(p.x(), p.y(), p.z())).toList();
        var intended = SableShipObservationCamera.overview(49, 17, 65);
        // Recovered head 054 camera transformed back through the reverse ordinary portal,
        // then incorrectly interpreted using the destination body pose. Even retaining the
        // intended look direction, this reproduces the empty visibility set.
        var wrongEye = new Point(-68.303590, -70, 44.966306);
        var wrongTarget = new Point(wrongEye.x() + intended.target().x() - intended.eye().x(),
            wrongEye.y() + intended.target().y() - intended.eye().y(),
            wrongEye.z() + intended.target().z() - intended.eye().z());
        assertTrue(visible(blocks, new View(wrongEye, wrongTarget), 160).isEmpty());
        assertEquals(blocks.stream().map(SableShipVisibleSectionsTest::section).collect(Collectors.toSet()),
            visible(blocks, SableShipObservationCamera.overviewAfterCrossing(49, 17, 65), 160));
    }

    @Test void translationToHiddenPlotPreservesPanelSelectionAndDistance() {
        var layout = SableFlightShipLayout.create(true);
        var blocks = layout.blocks().keySet().stream().map(p -> new Point(p.x(), p.y(), p.z())).toList();
        var panel = SableShipObservationCamera.panel();
        var expected = visible(blocks, panel, 48);
        assertFalse(expected.isEmpty());
        assertTrue(expected.size() < blocks.stream().map(SableShipVisibleSectionsTest::section).distinct().count());
        int dx = 20_481_024, dy = 128, dz = -20_481_024;
        assertEquals(expected.stream().map(s -> new Section(s.x() + dx / 16, s.y() + dy / 16, s.z() + dz / 16))
                .collect(Collectors.toSet()),
            visible(blocks.stream().map(p -> p.inPlot(dx, dy, dz)).toList(),
                new View(panel.eye().inPlot(dx, dy, dz), panel.target().inPlot(dx, dy, dz)), 48));
    }

    @Test void rotatedBodyFramePreservesVisibilityWithMatchingCameraUp() {
        List<Point> blocks = List.of(new Point(0, 0, 20), new Point(18, 0, 20), new Point(0, 18, 20));
        var normal = visible(blocks, FORWARD, 128);
        assertEquals(Set.of(new Section(0, 0, 1), new Section(1, 0, 1)), normal);
        // Cyclic 90-degree-axis permutation rotates cubes without changing lower-corner semantics.
        var rotatedBlocks = blocks.stream().map(SableShipVisibleSectionsTest::rotate).toList();
        var rotatedView = new View(rotate(FORWARD.eye()), rotate(FORWARD.target()));
        assertEquals(normal.stream().map(s -> new Section(s.z(), s.x(), s.y())).collect(Collectors.toSet()),
            SableShipVisibleSections.expectedSections(rotatedBlocks, rotatedView, new Point(0, 0, 1), FOV, ASPECT, 128));
    }

    @Test void rolledCameraSwapsWideAndNarrowFrustumAxes() {
        List<Point> blocks = List.of(new Point(18, 0, 20), new Point(0, 18, 20));
        assertEquals(Set.of(new Section(1, 0, 1)), visible(blocks, FORWARD, 128));
        assertEquals(Set.of(new Section(0, 1, 1)), SableShipVisibleSections.expectedSections(
            blocks, FORWARD, new Point(1, 0, 0), FOV, ASPECT, 128));
        assertEquals(Set.of(new Section(0, 1, 1)), SableShipVisibleSections.expectedSections(
            blocks, FORWARD, new Point(10, 0, 2), FOV, ASPECT, 128));
    }

    @Test void cameraParallelToUpHintHasAStableVerticalFallback() {
        View vertical = new View(new Point(.5, .5, .5), new Point(.5, 20, .5));
        assertEquals(Set.of(new Section(0, 1, 0)), visible(List.of(new Point(0, 20, 0)), vertical, 128));
    }

    @Test void resultCannotChangeWhenInputIsLaterModified() {
        var blocks = new ArrayList<>(List.of(new Point(0, 0, 20)));
        var result = visible(blocks, FORWARD, 128);
        blocks.clear();
        assertEquals(Set.of(new Section(0, 0, 1)), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(new Section(1, 1, 1)));
    }

    @Test void invalidProjectionCoordinatesAndDegenerateDirectionsFailClearly() {
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> SableShipVisibleSections.expectedSections(List.of(), FORWARD, invalid, ASPECT, 128));
            assertThrows(IllegalArgumentException.class, () -> SableShipVisibleSections.expectedSections(List.of(), FORWARD, FOV, invalid, 128));
            assertThrows(IllegalArgumentException.class, () -> visible(List.of(), FORWARD, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> SableShipVisibleSections.expectedSections(List.of(), FORWARD, 180, ASPECT, 128));
        assertThrows(IllegalArgumentException.class, () -> visible(List.of(), new View(FORWARD.eye(), FORWARD.eye()), 128));
        assertThrows(IllegalArgumentException.class, () -> SableShipVisibleSections.expectedSections(List.of(), FORWARD, new Point(0, 0, 0), FOV, ASPECT, 128));
        for (double invalid : new double[] {.5, Double.NaN, Double.POSITIVE_INFINITY, 1.0 + Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> visible(List.of(new Point(invalid, 0, 20)), FORWARD, 128));
        }
        assertThrows(IllegalArgumentException.class, () -> visible(List.of(), new View(new Point(Double.NaN, 0, 0), FORWARD.target()), 128));
    }

    private static Set<Section> visible(List<Point> blocks, View view, double distance) {
        return SableShipVisibleSections.expectedSections(blocks, view, FOV, ASPECT, distance);
    }
    private static Section section(Point block) {
        return new Section(Math.floorDiv((int) block.x(), 16), Math.floorDiv((int) block.y(), 16), Math.floorDiv((int) block.z(), 16));
    }
    private static Point rotate(Point point) { return new Point(point.z(), point.x(), point.y()); }
}
