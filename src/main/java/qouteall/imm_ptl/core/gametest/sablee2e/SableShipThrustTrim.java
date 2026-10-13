package qouteall.imm_ptl.core.gametest.sablee2e;

/** Pure planar thrust trim, independent of Minecraft, Sable, Create and addon jars. */
final class SableShipThrustTrim {
    private SableShipThrustTrim() {}

    /**
     * Returns positive integer RPM magnitudes in rotor order. Coefficients are
     * static force per RPM (including each rotor's actual local pressure).
     * Coordinates and COM must use the same frame; hidden-plot coordinates are valid.
     *
     * Uses strictly positive affine force weights, then normalizes and quantizes.
     * This conservative trim family deliberately rejects some centers inside the
     * convex hull when they would require zero or negative affine weights. It is
     * not a general constrained optimizer. Callers must check the resulting force
     * and moment against their own lift and integer-resolution tolerances.
     * Inputs are read-only; each call returns a fresh result array.
     */
    static int[] balanceMotorRpms(double[] x, double[] z, double[] coefficients,
        double centerX, double centerZ, int maximumRpm) {
        require(x != null && z != null && coefficients != null, "Rotor arrays must not be null");
        int count = x.length;
        require(count >= 3 && z.length == count && coefficients.length == count, "Invalid rotor geometry");
        require(maximumRpm > 0 && maximumRpm <= 256, "Invalid trim RPM limit");
        double meanX = 0, meanZ = 0;
        for (int i = 0; i < count; i++) {
            require(Double.isFinite(x[i]) && Double.isFinite(z[i]) && Double.isFinite(coefficients[i])
                && coefficients[i] > 0, "Invalid rotor position or pressure coefficient");
            meanX += x[i]; meanZ += z[i];
        }
        meanX /= count; meanZ /= count;
        double xx = 0, xz = 0, zz = 0;
        for (int i = 0; i < count; i++) {
            double dx = x[i] - meanX, dz = z[i] - meanZ;
            xx += dx * dx; xz += dx * dz; zz += dz * dz;
        }
        double determinant = xx * zz - xz * xz;
        require(Double.isFinite(centerX) && Double.isFinite(centerZ) && Double.isFinite(determinant) && determinant > 1.0e-9,
            "Cannot solve planar moment trim for degenerate rotor geometry");
        double targetX = count * (centerX - meanX), targetZ = count * (centerZ - meanZ);
        double a = (targetX * zz - targetZ * xz) / determinant;
        double b = (targetZ * xx - targetX * xz) / determinant;
        double[] unscaled = new double[count];
        double maximum = 0;
        for (int i = 0; i < count; i++) {
            double forceWeight = 1 + a * (x[i] - meanX) + b * (z[i] - meanZ);
            require(forceWeight > 0 && Double.isFinite(forceWeight), "Ship center of mass is outside positive rotor trim capacity");
            unscaled[i] = forceWeight / coefficients[i];
            require(Double.isFinite(unscaled[i]) && unscaled[i] > 0, "Invalid rotor force-per-RPM scale");
            maximum = Math.max(maximum, unscaled[i]);
        }
        int[] rpm = new int[count];
        double momentX = 0, momentZ = 0, totalForce = 0, targetForce = 0;
        for (int i = 0; i < count; i++) {
            double ideal = maximumRpm * unscaled[i] / maximum;
            rpm[i] = Math.max(1, Math.min(maximumRpm, (int) Math.round(ideal)));
            double force = coefficients[i] * rpm[i];
            momentX += force * (x[i] - centerX);
            momentZ += force * (z[i] - centerZ);
            totalForce += force;
            targetForce += coefficients[i] * ideal;
        }
        // One-RPM changes are the real motor UI's resolution. Minimize the remaining
        // planar moment with a tiny thrust penalty, without exceeding the control range.
        double scaleX = Math.sqrt(xx / count), scaleZ = Math.sqrt(zz / count);
        for (int iteration = 0; iteration < count * 8; iteration++) {
            double best = trimCost(momentX, momentZ, totalForce - targetForce, scaleX, scaleZ);
            int bestIndex = -1, bestDelta = 0;
            for (int i = 0; i < count; i++) for (int delta : new int[] {-1, 1}) {
                if (rpm[i] + delta < 1 || rpm[i] + delta > maximumRpm) continue;
                double forceDelta = delta * coefficients[i];
                double cost = trimCost(momentX + forceDelta * (x[i] - centerX),
                    momentZ + forceDelta * (z[i] - centerZ), totalForce + forceDelta - targetForce, scaleX, scaleZ);
                if (cost + 1.0e-12 < best) { best = cost; bestIndex = i; bestDelta = delta; }
            }
            if (bestIndex < 0) break;
            rpm[bestIndex] += bestDelta;
            double forceDelta = bestDelta * coefficients[bestIndex];
            momentX += forceDelta * (x[bestIndex] - centerX);
            momentZ += forceDelta * (z[bestIndex] - centerZ);
            totalForce += forceDelta;
        }
        return rpm;
    }

    private static double trimCost(double momentX, double momentZ, double forceError, double scaleX, double scaleZ) {
        return Math.pow(momentX / scaleX, 2) + Math.pow(momentZ / scaleZ, 2) + forceError * forceError * 1.0e-6;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
