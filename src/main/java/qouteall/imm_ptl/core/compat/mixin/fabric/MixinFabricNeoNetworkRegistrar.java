package qouteall.imm_ptl.core.compat.mixin.fabric;

import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Function;

/** Protect both the native-handler map and the shared NeoForge setup flag toggled by its factory. */
@Pseudo
@Mixin(targets = "org.sinytra.fabric.networking_api.NeoNetworkRegistrar", remap = false)
public class MixinFabricNeoNetworkRegistrar {
    @Redirect(method = "getOrRegisterNativeHandler", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object ip$registerNativeHandler(Map<Object, Object> handlers, Object id, Function<Object, Object> factory) {
        // All protocol registrars share NetworkRegistry.setup, so a per-map lock is insufficient.
        synchronized (NetworkRegistry.class) {
            return handlers.computeIfAbsent(id, factory);
        }
    }
}
