package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableShipBroadsideGeometryTest {
    @Test void bothHullsStartAndFinishEntirelyClearWithSeveralPartialPassageSamples() {
        for (boolean large:new boolean[]{false,true}) {
            var layout=SableFlightShipLayout.create(large);
            double origin=layout.halfWidth()+SableShipBroadsideGeometry.START_GAP;
            var start=bounds(layout,origin,48);
            assertEquals(20,start.minNormal());
            assertFalse(start.leading()); assertFalse(start.center()); assertFalse(start.trailing());
            int leading=0,center=0,trailing=0;
            for (double translation=0;translation<=start.maxNormal()+13;translation+=1.2) {
                var sample=bounds(layout,origin-translation,48);
                assertTrue(sample.fitsAperture());
                if (sample.leading()) leading++;
                if (sample.center()) center++;
                if (sample.trailing()) trailing++;
            }
            assertTrue(leading>=5,"Even CONTROL must give several straddling native ticks");
            assertTrue(center>=2,"A center crossing alone cannot stand in for a trailing-edge crossing");
            assertTrue(trailing>=10);
            assertEquals(-12,bounds(layout,origin-start.maxNormal()-12,48).maxNormal(),1e-9);
        }
    }

    @Test void ninetyDegreeYawRotatesBroadsideVelocityAndNoseTogetherAndReturnRestoresThem() {
        double[] nose={0,0,1},velocity={-24,18,0};
        double[] turnedNose=yaw90(nose),turnedVelocity=yaw90(velocity);
        assertArrayEquals(new double[]{1,0,0},turnedNose,1e-12);
        assertArrayEquals(new double[]{0,18,24},turnedVelocity,1e-12);
        assertEquals(0,dot(nose,velocity)); assertEquals(0,dot(turnedNose,turnedVelocity));
        assertArrayEquals(nose,inverseYaw90(turnedNose),1e-12);
        assertArrayEquals(velocity,inverseYaw90(turnedVelocity),1e-12);
        for (var layout:new SableFlightShipLayout[]{SableFlightShipLayout.create(false),SableFlightShipLayout.create(true)}) {
            // Projecting actual destination corners through the inverse seam preserves aperture and passage bounds.
            var before=bounds(layout,-10,60);
            var corners=corners(layout,-10,60);
            for (int i=0;i<corners.length;i++) {
                var p=corners[i];
                double[] world={p.z(),p.y()+64,-p.x()};
                double[] roundTrip=inverseYaw90(yaw90(world));
                corners[i]=new SableShipBroadsideGeometry.Point(-roundTrip[2],roundTrip[1]-64,roundTrip[0]);
            }
            assertEquals(before,SableShipBroadsideGeometry.bounds(corners));
        }
    }

    @Test void nativeGravityAndDampingPlanKeepsLargestBroadsideArcInsideClearedCorridor() {
        for (boolean large:new boolean[]{false,true}) {
            var layout=SableFlightShipLayout.create(large);
            double distance=layout.width()+20+12;
            for (double drag:new double[]{0,.09}) {
                var launch=SableShipBroadsideGeometry.launch(distance,-11,drag);
                assertEquals(0,SableShipBroadsideGeometry.verticalDisplacement(launch.upwardSpeed(),-11,drag,launch.duration()),1e-9);
                assertTrue(launch.upwardSpeed()<24,"Initial velocity is primarily sideways, with ballistic lift compensation");
                assertTrue(48+layout.height()+launch.maxRise()<97,"Entire native arc stays under cleared Nether ceiling");
                for (int i=0;i<=100;i++) {
                    double t=launch.duration()*i/100;
                    double dx=drag==0 ? 24*t : -24*Math.expm1(-drag*t)/drag;
                    double dy=SableShipBroadsideGeometry.verticalDisplacement(launch.upwardSpeed(),-11,drag,t);
                    var b=bounds(layout,layout.halfWidth()+20-dx,48+dy);
                    assertTrue(b.fitsAperture());
                    assertTrue(SableShipFlightCorridor.contains(b.minNormal(),48+dy,-layout.halfLength()));
                    assertTrue(SableShipFlightCorridor.contains(b.maxNormal(),48+dy+layout.height(),layout.halfLength()+1));
                }
            }
        }
    }

    @Test void apertureTestsCornersRatherThanOnlyTheCenter() {
        var corners=corners(SableFlightShipLayout.create(true),0,48);
        assertTrue(SableShipBroadsideGeometry.bounds(corners).fitsAperture());
        corners[0]=new SableShipBroadsideGeometry.Point(80,0,0);
        assertFalse(SableShipBroadsideGeometry.bounds(corners).fitsAperture());
        corners[0]=new SableShipBroadsideGeometry.Point(0,40,0);
        assertFalse(SableShipBroadsideGeometry.bounds(corners).fitsAperture());
        corners[0]=new SableShipBroadsideGeometry.Point(Double.NaN,0,0);
        assertThrows(IllegalArgumentException.class,()->SableShipBroadsideGeometry.bounds(corners));
        assertThrows(IllegalArgumentException.class,()->SableShipBroadsideGeometry.bounds(new SableShipBroadsideGeometry.Point[0]));
    }

    @Test void invalidOrUnreachableLaunchesFailClosed() {
        for (double distance:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,300})
            assertThrows(IllegalArgumentException.class,()->SableShipBroadsideGeometry.launch(distance,-11,.09));
        assertThrows(IllegalArgumentException.class,()->SableShipBroadsideGeometry.launch(80,0,.09));
        assertThrows(IllegalArgumentException.class,()->SableShipBroadsideGeometry.launch(80,-11,-.1));
    }

    private static SableShipBroadsideGeometry.Bounds bounds(SableFlightShipLayout layout,double x,double y) {
        return SableShipBroadsideGeometry.bounds(corners(layout,x,y));
    }
    private static SableShipBroadsideGeometry.Point[] corners(SableFlightShipLayout layout,double x,double y) {
        var result=new SableShipBroadsideGeometry.Point[8]; int i=0;
        for (int a:new int[]{-layout.halfWidth(),layout.halfWidth()+1})
            for (int b:new int[]{0,layout.height()}) for (int c:new int[]{-layout.halfLength(),layout.halfLength()+1})
                result[i++]=new SableShipBroadsideGeometry.Point(-c,y+b-64,x+a);
        return result;
    }
    private static double[] yaw90(double[] p) { return new double[]{p[2],p[1],-p[0]}; }
    private static double[] inverseYaw90(double[] p) { return new double[]{-p[2],p[1],p[0]}; }
    private static double dot(double[] a,double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
}
