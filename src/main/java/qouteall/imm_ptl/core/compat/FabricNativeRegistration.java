package qouteall.imm_ptl.core.compat;

import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

public final class FabricNativeRegistration {
    private FabricNativeRegistration() {}

    public static Object register(Map<Object, Object> handlers, Object id, Function<Object, Object> factory,
                                  BooleanSupplier readSetup, Consumer<Boolean> restoreSetup) {
        // Both protocol registrars temporarily change the same NeoForge setup flag.
        synchronized (NetworkRegistry.class) {
            boolean originalSetup = readSetup.getAsBoolean();
            try {
                return handlers.computeIfAbsent(id, factory);
            } finally {
                restoreSetup.accept(originalSetup);
            }
        }
    }
}
