package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SableFlightShipLayoutTest {
    @Test void controlAndLargeHaveDeterministicDistinctRealBlockCounts() {
        var control = SableFlightShipLayout.create(false);
        var large = SableFlightShipLayout.create(true);
        assertEquals(7, control.width());
        assertEquals(5, control.height());
        assertEquals(11, control.length());
        assertEquals(49, large.width());
        assertEquals(17, large.height());
        assertEquals(65, large.length());
        assertTrue(control.blocks().size() >= 150);
        assertTrue(large.blocks().size() >= 3500);
        assertTrue(large.blocks().size() > control.blocks().size() * 10);
        assertEquals(control.blocks(), SableFlightShipLayout.create(false).blocks());
        assertEquals(large.blocks(), SableFlightShipLayout.create(true).blocks());
        assertThrows(UnsupportedOperationException.class,
            () -> large.blocks().clear(), "Runtime mutation must not alter the immutable layout baseline");
    }

    @Test void largeCrossesBothHorizontalChunkAxesAndVerticalSectionsAtEveryChunkOffset() {
        var large = SableFlightShipLayout.create(true);
        for (int offsetX = 0; offsetX < 16; offsetX++) {
            for (int offsetZ = 0; offsetZ < 16; offsetZ++) {
                Set<String> chunks = new HashSet<>();
                Set<String> sections = new HashSet<>();
                for (var position : large.blocks().keySet()) {
                    String chunk = Math.floorDiv(position.x() + offsetX, 16) + ":"
                        + Math.floorDiv(position.z() + offsetZ, 16);
                    chunks.add(chunk);
                    sections.add(chunk + ":" + Math.floorDiv(position.y(), 16));
                }
                assertTrue(chunks.size() >= 12);
                assertTrue(sections.size() > chunks.size());
            }
        }
    }

    @Test void fixturesAreFaceConnectedAndKeepEveryFittingInsideTheExactEnvelope() {
        for (boolean big : new boolean[] {false, true}) {
            var layout = SableFlightShipLayout.create(big);
            for (var position : layout.blocks().keySet()) {
                assertTrue(Math.abs(position.x()) <= layout.halfWidth());
                assertTrue(Math.abs(position.z()) <= layout.halfLength());
                assertTrue(position.y() >= 0 && position.y() < layout.height());
            }
            var found = new HashSet<SableFlightShipLayout.Position>();
            var pending = new ArrayDeque<SableFlightShipLayout.Position>();
            pending.add(layout.blocks().keySet().iterator().next());
            while (!pending.isEmpty()) {
                var position = pending.removeFirst();
                if (!found.add(position)) continue;
                for (int[] offset : new int[][] {{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}}) {
                    var next = position.offset(offset[0], offset[1], offset[2]);
                    if (layout.blocks().containsKey(next) && !found.contains(next)) pending.add(next);
                }
            }
            var disconnected = new HashSet<>(layout.blocks().keySet());
            disconnected.removeAll(found);
            assertTrue(disconnected.isEmpty(), "Disconnected ship blocks: " + disconnected);
        }
    }

    @Test void bothFixturesHaveCompleteDriveTrainsCargoSeatAndClientUpdatePanel() {
        for (boolean big : new boolean[] {false, true}) {
            var layout = SableFlightShipLayout.create(big);
            assertEquals(2, layout.motors().size());
            for (var motor : layout.motors()) {
                assertEquals("create:creative_motor", layout.blocks().get(motor));
                assertEquals("create:shaft", layout.blocks().get(motor.offset(0, 0, 1)));
                assertEquals("create:cogwheel", layout.blocks().get(motor.offset(0, 0, 2)));
            }
            for (var cargo : layout.cargo()) assertEquals("minecraft:barrel", layout.blocks().get(cargo));
            assertEquals("create:red_seat", layout.blocks().get(layout.seat()));
            assertEquals("create:item_drain", layout.blocks().get(layout.drain()));
            for (int[] offset : new int[][] {{-1,0,0},{1,0,0},{0,0,-1},{0,0,1}}) {
                assertEquals("minecraft:spruce_planks", layout.blocks().get(layout.drain().offset(offset[0], offset[1], offset[2])),
                    "The loaded drain needs a solid stop on every potential export side");
            }
            for (int x = -1; x <= 1; x++) for (int y = 2; y <= 4; y++) {
                assertEquals("minecraft:lime_concrete", layout.blocks().get(new SableFlightShipLayout.Position(x, y, 2)));
            }
            assertEquals("minecraft:lever", layout.blocks().get(new SableFlightShipLayout.Position(-2, 1, 0)));
            assertEquals("minecraft:redstone_lamp", layout.blocks().get(new SableFlightShipLayout.Position(-2, 0, 0)));
            assertEquals("minecraft:white_concrete", layout.blocks().get(new SableFlightShipLayout.Position(-2, 2, 2)));
        }
    }
}
