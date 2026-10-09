package qouteall.imm_ptl.core.compat;

import java.util.Map;
import java.util.function.Supplier;

/** Serializes only registration's upstream containsKey/put pair, never packet dispatch. */
public final class FabricReceiverRegistration {
    private FabricReceiverRegistration() {}

    public static boolean register(Map<?, ?> receivers, Supplier<Boolean> operation) {
        synchronized (receivers) {
            return operation.get();
        }
    }

    public static void checkVersion(String version) {
        if (!"4.2.2+a92978fd19".equals(version)) {
            throw new IllegalStateException("Immersive Portals: unsupported Forgified Fabric networking " + version
                + "; receiver compatibility requires 4.2.2+a92978fd19 (bundled by owo-lib 0.12.15-beta.12).");
        }
    }
}
