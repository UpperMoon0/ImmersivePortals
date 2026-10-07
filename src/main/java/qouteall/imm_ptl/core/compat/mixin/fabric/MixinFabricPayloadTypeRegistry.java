package qouteall.imm_ptl.core.compat.mixin.fabric;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forgified Fabric API is called from parallel NeoForge mod constructors.
 * Its ordinary HashMap can lose payload types even between register() and get().
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", remap = false)
public class MixinFabricPayloadTypeRegistry {
    @Shadow @Final @Mutable
    private Map<Object, Object> packetTypes;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ip$makeRegistryConcurrent(CallbackInfo ci) {
        packetTypes = new ConcurrentHashMap<>(packetTypes);
    }

    // containsKey + put is not atomic, even after replacing the backing map.
    // Keep the original duplicate check and reject a racing duplicate here too.
    @Redirect(method = "register", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object ip$registerAtomically(Map<Object, Object> types, Object id, Object codec) {
        Object previous = types.putIfAbsent(id, codec);
        if (previous != null) {
            throw new IllegalArgumentException("Packet type " + id + " is already registered!");
        }
        return null;
    }
}
