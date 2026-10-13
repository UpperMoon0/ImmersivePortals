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

}
