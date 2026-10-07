package qouteall.imm_ptl.core.gametest.sablee2e;

/** Normalized pixel regions for the fixed oblique-camera shader witnesses. */
public final class PortalSmokePixelRegions {
    private PortalSmokePixelRegions() {}

    public static double[] controlRegion(String scene) {
        if (scene.equals("entity-visible")) {
            // Moving the fully retained panel back to z=-6 projects its center to
            // the right. Captured 854x480 bounds are x434..678; sample its interior.
            return new double[]{0.60, 0.45, 0.66, 0.55};
        }
        if (scene.equals("particle-visible")) {
            return new double[]{0.65, 0.45, 0.70, 0.55};
        }
        if (scene.equals("entity-clipped") || scene.equals("particle-clipped")) {
            // This excluded-side region is deliberately unchanged by positive-control calibration.
            return new double[]{0.39, 0.45, 0.45, 0.55};
        }
        throw new IllegalArgumentException("No straddling witness region for " + scene);
    }

    public record Difference(double meanAbsoluteError, double changedFraction, int sampledPixels, double actualBrightness) {
        public boolean matchesBackground() { return actualBrightness > 2.0 && meanAbsoluteError <= 4.0 && changedFraction <= 0.01; }
        public boolean visiblyDifferent() {
            // A changed pixel already requires a summed RGB difference greater than 12.
            // Averaging again over the whole region wrongly rejects small, dark Create
            // parts surrounded by an unchanged backdrop. Retain the 10% contrast area,
            // nonblack output and the mathematical lower bound for those changed pixels.
            return actualBrightness > 2.0 && changedFraction >= 0.10
                && meanAbsoluteError + 1e-9 >= changedFraction * 13.0 / 3.0;
        }
    }

    public static Difference difference(int[] reference, int[] actual) {
        if (reference.length != actual.length || actual.length == 0) throw new IllegalArgumentException("Mismatched pixel regions");
        long error = 0;
        int changed = 0;
        for (int i = 0; i < actual.length; i++) {
            int delta = 0;
            for (int shift : new int[]{0, 8, 16}) delta += Math.abs(((reference[i] >>> shift) & 255) - ((actual[i] >>> shift) & 255));
            error += delta;
            if (delta > 12) changed++;
        }
        double[] color = meanColor(actual);
        return new Difference(error / (actual.length * 3.0), changed / (double) actual.length, actual.length,
            (color[0] + color[1] + color[2]) / 3.0);
    }

    public static double[] meanColor(int[] pixels) {
        double[] result = new double[3];
        for (int pixel : pixels) for (int channel = 0; channel < 3; channel++) result[channel] += (pixel >>> (channel * 8)) & 255;
        for (int channel = 0; channel < 3; channel++) result[channel] /= pixels.length;
        return result;
    }

    public static double chromaticDistance(double[] left, double[] right) {
        double leftSum = left[0] + left[1] + left[2], rightSum = right[0] + right[1] + right[2];
        if (leftSum <= 0 || rightSum <= 0) return Double.POSITIVE_INFINITY;
        double result = 0;
        for (int channel = 0; channel < 3; channel++) result += Math.pow(left[channel] / leftSum - right[channel] / rightSum, 2);
        return result;
    }

    public static boolean matchesCrossingPalette(double[] background, double[] visible, double[] actual) {
        // The native destination camera is closer to the wall, so fog brightness is not
        // identical. Require the backdrop's green/blue separation and greater chromatic
        // similarity to that backdrop than the independently captured red occluder.
        return (actual[0] + actual[1] + actual[2]) / 3 > 2
            && actual[1] / (actual[2] + 1) >= 0.8 * background[1] / (background[2] + 1)
            && chromaticDistance(actual, background) < 0.75 * chromaticDistance(actual, visible);
    }

    public static String referenceScene(String scene) {
        if (scene.endsWith("-background")) return scene;
        if (scene.startsWith("solid-")) return "solid-background";
        if (scene.startsWith("nested")) return "nested-background";
        if (scene.startsWith("mirror")) return "mirror-background";
        if (scene.equals("create-nested")) return "create-nested-background";
        if (scene.startsWith("create-")) return "create-background";
        return null;
    }

    public record DepthTarget(String key, double backdropDistance) {}

    public static DepthTarget depthTarget(String referenceScene) {
        return switch (referenceScene) {
            case "solid-background", "create-background" -> new DepthTarget("minecraft:the_nether:1", 7);
            case "nested-background" -> new DepthTarget("minecraft:the_end:2", 8);
            case "create-nested-background" -> new DepthTarget("minecraft:the_nether:2", 8);
            case "mirror-background" -> new DepthTarget("minecraft:overworld:1", 10);
            default -> throw new IllegalArgumentException("No depth target for " + referenceScene);
        };
    }

    public static String depthExpectation(String scene) {
        if (scene.endsWith("-background") || expectsBackground(scene) || scene.equals("mirror-visible")) return "same";
        if (scene.startsWith("create-")) return "moving-nearer";
        return "nearer";
    }

    public static boolean expectsBackground(String scene) {
        return scene.endsWith("-clipped") || scene.equals("nested") || scene.equals("mirror");
    }

    public static double[] retainedRegion() {
        return new double[]{0.56, 0.45, 0.62, 0.55};
    }
}
