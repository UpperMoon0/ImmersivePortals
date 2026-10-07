package qouteall.imm_ptl.core.gametest.sablee2e;

/** Measures cracks on the known stationary cog face, with its adjacent backdrop as a control. */
public final class PortalSmokeCrumblingOracle {
    private PortalSmokeCrumblingOracle() {}

    public static boolean facePixel(int x, int y, int width, int height) {
        return radiusSquared(x, y, width, height) <= 0.13 * 0.13;
    }

    public static boolean backgroundPixel(int x, int y, int width, int height) {
        double radius = radiusSquared(x, y, width, height);
        return radius >= 0.18 * 0.18 && radius <= 0.23 * 0.23;
    }

    private static double radiusSquared(int x, int y, int width, int height) {
        // Block(0,82,-1), viewed from(0,82,4), FOV70: face center is about
        // (width/2 + .08*height, .42*height). Height-based radii survive resize/aspect changes.
        double dx = (x - (width * 0.5 + height * 0.08)) / height;
        double dy = (y - height * 0.42) / height;
        return dx * dx + dy * dy;
    }

    public record Measurement(int samples, int darkenedPixels, int brightenedPixels, int unchangedPixels,
        double meanDarkening, double darkeningEnergy, double brighteningEnergy,
        double meanAbsoluteChange, double changedFraction, double backgroundDrift, double backgroundResidual) {
        public boolean backgroundStable() {
            return Math.abs(backgroundDrift) <= 1.0 && backgroundResidual <= 1.0;
        }
        public boolean showsDamage() {
            return backgroundStable() && meanDarkening > 0.5
                && darkenedPixels >= Math.max(20, samples * 0.02)
                && unchangedPixels >= samples * 0.05
                && darkeningEnergy > 2 * brighteningEnergy;
        }
        public boolean restored() {
            return backgroundStable() && meanAbsoluteChange <= 1.0 && changedFraction <= 0.01;
        }
    }

    public static Measurement compare(int[] cleanFace, int[] currentFace, int[] cleanBackground, int[] currentBackground) {
        if (cleanFace.length == 0 || cleanFace.length != currentFace.length
            || cleanBackground.length == 0 || cleanBackground.length != currentBackground.length) {
            throw new IllegalArgumentException("Mismatched crumbling masks");
        }
        double drift = 0;
        for (int i = 0; i < cleanBackground.length; i++) drift += brightness(cleanBackground[i]) - brightness(currentBackground[i]);
        drift /= cleanBackground.length;
        double backgroundResidual = 0;
        for (int i = 0; i < cleanBackground.length; i++) backgroundResidual += Math.abs(brightness(cleanBackground[i]) - brightness(currentBackground[i]) - drift);
        backgroundResidual /= cleanBackground.length;
        int dark = 0, bright = 0, unchanged = 0, changed = 0;
        double total = 0, absolute = 0, darkEnergy = 0, brightEnergy = 0;
        for (int i = 0; i < cleanFace.length; i++) {
            double delta = brightness(cleanFace[i]) - brightness(currentFace[i]) - drift;
            total += delta;
            absolute += Math.abs(delta);
            if (delta > 4) { dark++; darkEnergy += delta; }
            if (delta < -4) { bright++; brightEnergy -= delta; }
            if (Math.abs(delta) <= 2) unchanged++;
            if (Math.abs(delta) > 4) changed++;
        }
        return new Measurement(cleanFace.length, dark, bright, unchanged, total / cleanFace.length,
            darkEnergy, brightEnergy, absolute / cleanFace.length, changed / (double) cleanFace.length, drift, backgroundResidual);
    }

    private static double brightness(int rgba) {
        return ((rgba & 255) + ((rgba >>> 8) & 255) + ((rgba >>> 16) & 255)) / 3.0;
    }
}
