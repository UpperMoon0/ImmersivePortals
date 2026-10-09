package qouteall.imm_ptl.core.compat.mixin.fabric;

import qouteall.imm_ptl.core.compat.FabricNativeRegistration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;

/** Protect both the native-handler map and the shared NeoForge setup flag toggled by its factory. */
@Pseudo
@Mixin(targets = "org.sinytra.fabric.networking_api.NeoNetworkRegistrar", remap = false)
public class MixinFabricNeoNetworkRegistrar {
    @Shadow @Final @Mutable private Map<Object, Object> registeredPayloads;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ip$publishHandlersSafely(CallbackInfo ci) {
        registeredPayloads = new ConcurrentHashMap<>(registeredPayloads);
    }

    @Redirect(method = "getOrRegisterNativeHandler", at = @At(value = "INVOKE",
        target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"))
    private Object ip$registerNativeHandler(Map<Object, Object> handlers, Object id, Function<Object, Object> factory) {
        return FabricNativeRegistration.register(handlers, id, factory,
            AccessorNeoForgeNetworkRegistry::ip$getSetup, AccessorNeoForgeNetworkRegistry::ip$setSetup);
    }
}
