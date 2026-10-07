package qouteall.imm_ptl.core.compat.iris_compatibility;

import java.util.Objects;

/** Cold-start default only; dimension changes and pipeline lifetime remain owned by NeOculus. */
public final class NeOculusDimensionState {
    private NeOculusDimensionState() {}

    public static <T> T initializeIfAbsent(T rememberedDimension, T initialDimension) {
        return rememberedDimension != null ? rememberedDimension : Objects.requireNonNull(initialDimension);
    }
}
