package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SableShipThrustTrimTest {
    private record Rotors(double[] x, double[] z, double[] coefficients) {}

    // Independent physical witness, not a second implementation of the solver.
    private record Resultant(double force, double momentX, double momentZ) {
        double offset() { return Math.hypot(momentX, momentZ) / force; }
    }

    @TestFactory List<DynamicTest> bothRealEngineGridsBalanceAtHiddenPlotCoordinates() {
        var tests = new ArrayList<DynamicTest>();
        for (boolean large : new boolean[] {false, true}) {
            for (double offsetX : new double[] {0, 0.06, 0.17}) {
                for (double offsetZ : new double[] {0, 0.11, 0.29}) {
                    tests.add(DynamicTest.dynamicTest((large ? "252 engines" : "8 engines")
                        + " COM offset " + offsetX + "," + offsetZ, () -> {
                        var rotors = grid(large, 30_000_000.5, 30_000_000.5);
                        double centerX = 30_000_000.5 + offsetX;
                        double centerZ = 30_000_000.5 + offsetZ;
                        int[] rpm = solve(rotors, centerX, centerZ, 256);
                        assertBounded(rpm, large ? 252 : 8, 256);
                        var witness = resultant(rotors, rpm, centerX, centerZ);
                        assertTrue(witness.force() > 0);
                        // Ten times stricter than the live fixture's 0.02-block gate.
                        assertTrue(witness.offset() < 0.002, "Integer moment residual: " + witness.offset());
                        assertArrayEquals(rpm, solve(rotors, centerX, centerZ, 256), "Trim must be deterministic");
                    }));
                }
            }
        }
        return tests;
    }

    @Test void unequalPressureIsCompensatedWithActualIndividualMotorControls() {
        var rotors = new Rotors(new double[] {-1, -1, 1, 1}, new double[] {-1, 1, -1, 1},
            new double[] {0.5, 1, 0.5, 1});
        int[] rpm = solve(rotors, 0, 0, 256);
        assertArrayEquals(new int[] {256, 128, 256, 128}, rpm);
        var witness = resultant(rotors, rpm, 0, 0);
        assertEquals(512, witness.force(), 1.0e-12);
        assertEquals(0, witness.offset(), 1.0e-12);
    }

    @Test void translationToLargePositiveAndNegativePlotCoordinatesPreservesCommands() {
        for (boolean large : new boolean[] {false, true}) {
            int[] local = solve(grid(large, 0.5, 0.5), 0.625, 0.75, 256);
            for (double origin : new double[] {-1_900_000_000, -30_000_000, 30_000_000, 1_900_000_000}) {
                assertArrayEquals(local, solve(grid(large, origin + 0.5, -origin + 0.5),
                    origin + 0.625, -origin + 0.75, 256));
            }
        }
    }

    @Test void integerRoundingRetainsMomentToleranceAtFlightThrottleSettings() {
        for (boolean large : new boolean[] {false, true}) {
            var rotors = grid(large, 0.5, 0.5);
            for (int maximum : new int[] {32, 64, 128, 192, 256}) {
                int[] rpm = solve(rotors, 0.67, 0.79, maximum);
                assertBounded(rpm, large ? 252 : 8, maximum);
                var witness = resultant(rotors, rpm, 0.67, 0.79);
                assertTrue(witness.offset() < 0.02,
                    "Integer trim exceeds runtime tolerance, engines=" + rpm.length + ", max=" + maximum
                        + ", offset=" + witness.offset());
            }
        }
    }

    @Test void equalPressureAndCenteredMassUseTheWholeAvailableRpmRange() {
        for (int maximum : new int[] {1, 16, 32, 128, 256}) {
            var rotors = square();
            int[] rpm = solve(rotors, 0, 0, maximum);
            assertArrayEquals(new int[] {maximum, maximum, maximum, maximum}, rpm);
            assertEquals(0, resultant(rotors, rpm, 0, 0).offset(), 1.0e-12);
        }
    }

    @Test void solverDoesNotModifyCallerInputsOrReuseReturnedArrays() {
        var rotors = grid(false, 30_000_000.5, 30_000_000.5);
        double[] originalX = rotors.x().clone(), originalZ = rotors.z().clone();
        double[] originalCoefficients = rotors.coefficients().clone();
        int[] first = solve(rotors, 30_000_000.67, 30_000_000.79, 256);
        int[] second = solve(rotors, 30_000_000.67, 30_000_000.79, 256);
        assertNotSame(first, second);
        first[0] = -999;
        assertTrue(second[0] > 0);
        assertArrayEquals(originalX, rotors.x());
        assertArrayEquals(originalZ, rotors.z());
        assertArrayEquals(originalCoefficients, rotors.coefficients());
    }

    @Test void degenerateCollinearAndCoincidentRotorLayoutsFailClosed() {
        assertThrows(IllegalStateException.class, () -> solve(new Rotors(
            new double[] {-1, 0, 1}, new double[] {0, 0, 0}, new double[] {1, 1, 1}), 0, 0, 256));
        assertThrows(IllegalStateException.class, () -> solve(new Rotors(
            new double[] {-1, 0, 1}, new double[] {-1, 0, 1}, new double[] {1, 1, 1}), 0, 0, 256));
        assertThrows(IllegalStateException.class, () -> solve(new Rotors(
            new double[] {1, 1, 1}, new double[] {1, 1, 1}, new double[] {1, 1, 1}), 1, 1, 256));
    }

    @Test void centersOutsideStrictlyPositiveAffineTrimCapacityFailClosed() {
        for (double[] center : new double[][] {{20, 0}, {0, -20}, {1, 0}, {0.9, 0.9}}) {
            // The last case is inside the square's convex hull but outside this
            // solver's conservative positive affine family; rejection is intentional.
            assertThrows(IllegalStateException.class, () -> solve(square(), center[0], center[1], 256));
        }
        int[] inside = solve(square(), 0.75, 0, 256);
        assertBounded(inside, 4, 256);
        assertTrue(resultant(square(), inside, 0.75, 0).offset() < 0.002);
    }

    @Test void invalidInputsAndNoAtmosphereFailClosed() {
        for (int maximum : new int[] {-1, 0, 257}) {
            assertThrows(IllegalStateException.class, () -> solve(square(), 0, 0, maximum));
        }
        assertThrows(IllegalStateException.class, () -> SableShipThrustTrim.balanceMotorRpms(
            null, new double[3], new double[3], 0, 0, 256));
        assertThrows(IllegalStateException.class, () -> solve(new Rotors(
            new double[2], new double[2], new double[2]), 0, 0, 256));
        assertThrows(IllegalStateException.class, () -> solve(new Rotors(
            new double[4], new double[3], new double[4]), 0, 0, 256));
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var rotors = square();
            rotors.coefficients()[0] = invalid;
            assertThrows(IllegalStateException.class, () -> solve(rotors, 0, 0, 256));
        }
        for (double invalid : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalStateException.class, () -> solve(square(), invalid, 0, 256));
            assertThrows(IllegalStateException.class, () -> solve(square(), 0, invalid, 256));
            var rotors = square();
            rotors.x()[0] = invalid;
            assertThrows(IllegalStateException.class, () -> solve(rotors, 0, 0, 256));
        }
    }

    private static Rotors square() {
        return new Rotors(new double[] {-1, -1, 1, 1}, new double[] {-1, 1, -1, 1}, new double[] {1, 1, 1, 1});
    }

    private static Rotors grid(boolean large, double originX, double originZ) {
        var positions = new ArrayList<double[]>();
        if (large) {
            for (int x = -21; x <= 21; x += 3) if (x != 0) {
                for (int z = -27; z <= 27; z += 3) if (z != 0) positions.add(new double[] {x, z, 0.94});
            }
        } else {
            for (int x : new int[] {-3, 0, 3}) for (int z : new int[] {-4, 0, 4}) {
                // The two centerline propellers are one block higher, hence lower pressure.
                if (x != 0 || z != 0) positions.add(new double[] {x, z, x == 0 ? 0.936 : 0.94});
            }
        }
        double[] x = new double[positions.size()], z = new double[x.length], coefficients = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = originX + positions.get(i)[0];
            z[i] = originZ + positions.get(i)[1];
            coefficients[i] = positions.get(i)[2];
        }
        return new Rotors(x, z, coefficients);
    }

    private static int[] solve(Rotors rotors, double centerX, double centerZ, int maximumRpm) {
        return SableShipThrustTrim.balanceMotorRpms(rotors.x(), rotors.z(), rotors.coefficients(),
            centerX, centerZ, maximumRpm);
    }

    private static void assertBounded(int[] rpm, int count, int maximum) {
        assertEquals(count, rpm.length);
        assertTrue(Arrays.stream(rpm).allMatch(value -> value >= 1 && value <= maximum));
    }

    private static Resultant resultant(Rotors rotors, int[] rpm, double centerX, double centerZ) {
        double force = 0, momentX = 0, momentZ = 0;
        for (int i = 0; i < rpm.length; i++) {
            double contribution = rpm[i] * rotors.coefficients()[i];
            force += contribution;
            momentX += contribution * (rotors.x()[i] - centerX);
            momentZ += contribution * (rotors.z()[i] - centerZ);
        }
        return new Resultant(force, momentX, momentZ);
    }
}
