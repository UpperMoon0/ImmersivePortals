package qouteall.imm_ptl.core.compat.iris_compatibility;

/** Context-free copy preconditions, shared with regression tests. */
final class FramebufferCopyPlan {
    private FramebufferCopyPlan() {}

    static boolean supportsCopyImage(boolean openGL43, boolean arbCopyImage,
        boolean entryPointAvailable, boolean forcedBlit) {
        // Some GL loaders expose addresses for commands unsupported by the current context.
        return (openGL43 || arbCopyImage) && entryPointAvailable && !forcedBlit;
    }

    static boolean useCopyImage(boolean available, boolean wholeTexture) {
        return available && wholeTexture;
    }

    static void requireMatchingStorage(int sourceFormat, int destinationFormat,
        int sourceWidth, int sourceHeight, int destinationWidth, int destinationHeight) {
        if (sourceFormat != destinationFormat || sourceWidth <= 0 || sourceHeight <= 0
            || sourceWidth != destinationWidth || sourceHeight != destinationHeight) {
            throw new IllegalStateException("Immersive Portals: framebuffer copy requires identical formats and dimensions"
                + " (source=0x" + Integer.toHexString(sourceFormat) + " " + sourceWidth + "x" + sourceHeight
                + ", destination=0x" + Integer.toHexString(destinationFormat) + " "
                + destinationWidth + "x" + destinationHeight + ")");
        }
    }
}
