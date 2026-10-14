package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableShipFlightCorridorTest {
    @Test void fixedEnvelopeHasBoundedKnownSetupCost() {
        assertEquals(6_406_753,SableShipFlightCorridor.VOLUME);
        assertEquals(196,(SableShipFlightCorridor.VOLUME+SableShipFlightCorridor.POSITIONS_PER_TICK-1)
            /SableShipFlightCorridor.POSITIONS_PER_TICK);
        assertEquals(50_000_000L,SableShipFlightCorridor.SOFT_NANOS_PER_TICK);
    }

    @Test void everyPositionIsVisitedExactlyOnceInTheInclusiveEnvelope() {
        for (int i=0;i<SableShipFlightCorridor.VOLUME;i++) {
            int x=SableShipFlightCorridor.x(i),y=SableShipFlightCorridor.y(i),z=SableShipFlightCorridor.z(i);
            assertTrue(x>=-128 && x<=128 && y>=0 && y<=96 && z>=-128 && z<=128);
            assertEquals(i,((x+128)*257+(z+128))*97+y);
        }
    }

    @Test void cursorStopsExactlyAtTheDeclaredVolume() {
        assertEquals(-128,SableShipFlightCorridor.x(0));
        assertEquals(0,SableShipFlightCorridor.y(0));
        assertEquals(-128,SableShipFlightCorridor.z(0));
        int last=SableShipFlightCorridor.VOLUME-1;
        assertEquals(128,SableShipFlightCorridor.x(last));
        assertEquals(96,SableShipFlightCorridor.y(last));
        assertEquals(128,SableShipFlightCorridor.z(last));
        for (int invalid:new int[]{-1,SableShipFlightCorridor.VOLUME,Integer.MAX_VALUE}) {
            assertThrows(IndexOutOfBoundsException.class,()->SableShipFlightCorridor.x(invalid));
            assertThrows(IndexOutOfBoundsException.class,()->SableShipFlightCorridor.y(invalid));
            assertThrows(IndexOutOfBoundsException.class,()->SableShipFlightCorridor.z(invalid));
        }
    }

    @Test void currentPortalAndLargestFixtureFitWithRotationalMargin() {
        var layout=SableFlightShipLayout.create(true);
        double radius=Math.sqrt(Math.pow(layout.halfWidth()+1,2)+Math.pow(layout.halfLength()+1,2)
            +Math.pow(layout.height(),2));
        assertTrue(radius<45);
        assertTrue(80+radius<SableShipFlightCorridor.MAX_XZ);
        assertTrue(48+radius<SableShipFlightCorridor.MAX_Y);
    }

    @Test void knownFailingArrivalNowFallsInsidePreparedTerrain() {
        assertTrue(SableShipFlightCorridor.contains(56.283332149,43.461638867,21.890762439));
        assertTrue(SableShipFlightCorridor.contains(-128,0,-128));
        assertTrue(SableShipFlightCorridor.contains(129,97,129),"Exclusive block maxima bound the cleared volume");
        for (double[] point:new double[][]{{-128.001,20,0},{129.001,20,0},{0,-.001,0},{0,97.001,0},
            {0,20,-128.001},{0,20,129.001},{Double.NaN,20,0},{0,Double.POSITIVE_INFINITY,0},
            {0,20,Double.NEGATIVE_INFINITY}}) assertFalse(SableShipFlightCorridor.contains(point[0],point[1],point[2]));
    }
}
