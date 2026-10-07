package qouteall.imm_ptl.core.gametest.sablee2e;

/** Deterministic receiver and shadow-map geometry; no driver or optional-mod dependencies. */
public final class PortalShadowOracle {
    private PortalShadowOracle() {}
    public static final double LIT_DEPTH = 0.5 + 3.0 / 64.0;
    public static final double CASTER_DEPTH = 0.5 - 2.0 / 64.0;

    public static boolean accepts(boolean caster, double green, double blue, double red,
        double sideGreen, double shadowDepth, double receiverDistance, boolean restored) {
        return restored && Double.isFinite(shadowDepth) && Double.isFinite(receiverDistance)
            && Math.abs(shadowDepth - (caster ? CASTER_DEPTH : LIT_DEPTH)) < 0.004
            && Math.abs(receiverDistance - 7) < 0.5
            && red < 0.01 && sideGreen > 0.80
            && (caster ? blue > 0.80 && green < 0.02 : green > 0.80 && blue < 0.02);
    }
}
