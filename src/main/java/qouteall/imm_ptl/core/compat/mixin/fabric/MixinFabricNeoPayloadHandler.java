package qouteall.imm_ptl.core.compat.mixin.fabric;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import org.apache.commons.lang3.function.TriConsumer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.FabricReceiverRegistration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Forgified Fabric networking 4.2.2+a92978fd19; retain upstream callback/duplicate semantics. */
@Pseudo
@Mixin(targets = "org.sinytra.fabric.networking_api.NeoNetworkRegistrar$NeoPayloadHandler", remap = false)
public class MixinFabricNeoPayloadHandler {
    @Shadow @Final @Mutable private Map<Object, Object> globalReceivers;
    @Shadow @Final @Mutable private Map<Object, Object> localReceivers;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ip$publishReceiversSafely(CallbackInfo ci) {
        globalReceivers = new ConcurrentHashMap<>(globalReceivers);
        localReceivers = new ConcurrentHashMap<>(localReceivers);
    }

    @WrapMethod(method = "registerGlobalHandler")
    private boolean ip$registerGlobal(PacketFlow flow, Object handler, Function<?, ?> factory,
                                     TriConsumer<?, ?, ?> consumer, Operation<Boolean> original) {
        return FabricReceiverRegistration.register(globalReceivers, () -> original.call(flow, handler, factory, consumer));
    }

    @WrapMethod(method = "registerLocalReceiver")
    private boolean ip$registerLocal(ICommonPacketListener listener, Object handler, Function<?, ?> factory,
                                    TriConsumer<?, ?, ?> consumer, Operation<Boolean> original) {
        return FabricReceiverRegistration.register(localReceivers, () -> original.call(listener, handler, factory, consumer));
    }
}
