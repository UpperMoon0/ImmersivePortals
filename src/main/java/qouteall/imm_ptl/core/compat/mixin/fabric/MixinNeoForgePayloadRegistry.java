package qouteall.imm_ptl.core.compat.mixin.fabric;

import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Forgified Fabric registration also writes NeoForge's registry from parallel mod constructors. */
@Mixin(value = NetworkRegistry.class, remap = false)
public class MixinNeoForgePayloadRegistry {
    @Shadow @Final @Mutable
    private static Map<Object, Map<Object, Object>> PAYLOAD_REGISTRATIONS;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void ip$makeRegistryConcurrent(CallbackInfo ci) {
        Map<Object, Map<Object, Object>> protocols = new java.util.HashMap<>();
        PAYLOAD_REGISTRATIONS.forEach((protocol, registrations) ->
            protocols.put(protocol, new ConcurrentHashMap<>(registrations)));
        PAYLOAD_REGISTRATIONS = Map.copyOf(protocols);
    }

    @Redirect(method = "register", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object ip$registerAtomically(Map<Object, Object> types, Object id, Object registration) {
        if (types.putIfAbsent(id, registration) != null) {
            throw new UnsupportedOperationException("Cannot register payload " + id + " as it is already registered.");
        }
        return null;
    }
}
