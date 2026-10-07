package qouteall.imm_ptl.core.compat.iris_compatibility;

/** Narrow workaround for Iris/NeOculus labeling a deliberately absent RenderTarget depth texture. */
public final class IrisDebugLabelPolicy {
    private static final int GL_TEXTURE = 0x1702;

    private IrisDebugLabelPolicy() {}

    public static boolean shouldLabel(int identifier, int object, String label) {
        return !(identifier == GL_TEXTURE && object == -1 && "Main depth texture".equals(label));
    }
}
