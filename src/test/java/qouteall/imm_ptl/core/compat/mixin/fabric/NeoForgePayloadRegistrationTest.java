package qouteall.imm_ptl.core.compat.mixin.fabric;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class NeoForgePayloadRegistrationTest {
    @Test
    @SuppressWarnings("unchecked")
    void parallelFabricReceiversPreserveNativeHandlersAndNegotiationPayloads() throws Exception {
        var field = MixinNeoForgePayloadRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        field.setAccessible(true);
        field.set(null, Map.of("configuration", new HashMap<>(), "play", new HashMap<>(Map.of("existing", "codec"))));
        var initialize = MixinNeoForgePayloadRegistry.class.getDeclaredMethod("ip$makeRegistryConcurrent", CallbackInfo.class);
        initialize.setAccessible(true);
        initialize.invoke(null, new CallbackInfo("<clinit>", false));
        var protocols = (Map<Object, Map<Object, Object>>) field.get(null);
        var register = MixinNeoForgePayloadRegistry.class.getDeclaredMethod("ip$registerAtomically", Map.class, Object.class, Object.class);
        register.setAccessible(true);
        var nativeRegister = MixinFabricNeoNetworkRegistrar.class.getDeclaredMethod("ip$registerNativeHandler", Map.class, Object.class, Function.class);
        nativeRegister.setAccessible(true);
        var registrars = Map.of("configuration", new MixinFabricNeoNetworkRegistrar(), "play", new MixinFabricNeoNetworkRegistrar());
        var factoryActive = new java.util.concurrent.atomic.AtomicBoolean();
        Map<String, Map<Object, Object>> handlers = Map.of("configuration", new HashMap<>(), "play", new HashMap<>());
        var barrier = new CyclicBarrier(16);
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 16; worker++) {
                final int slot = worker;
                tasks.add(pool.submit(() -> {
                    barrier.await();
                    for (String protocol : handlers.keySet()) {
                        for (int i = 0; i < 1000; i++) {
                            String id = slot + ":" + i;
                            Function<Object, Object> factory = key -> {
                                assertTrue(factoryActive.compareAndSet(false, true), "protocol factories must serialize the shared setup flag");
                                try { register.invoke(null, protocols.get(protocol), key, key); }
                                catch (Exception error) { throw new AssertionError(error); }
                                finally { factoryActive.set(false); }
                                return key;
                            };
                            assertEquals(id, nativeRegister.invoke(registrars.get(protocol), handlers.get(protocol), id, factory));
                            // A repeated receiver must reuse its handler without registering a duplicate payload.
                            assertEquals(id, nativeRegister.invoke(registrars.get(protocol), handlers.get(protocol), id, factory));
                        }
                    }
                    return null;
                }));
            }
            for (var task : tasks) task.get();
        }
        for (String protocol : handlers.keySet()) {
            assertEquals(16000, handlers.get(protocol).size());
            var payloads = protocols.get(protocol);
            assertEquals(protocol.equals("play") ? 16001 : 16000, payloads.size());
            assertEquals(payloads.size(), payloads.values().stream().map(Object::toString).toList().size());
        }
        assertEquals("codec", protocols.get("play").get("existing"));
        var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
            () -> register.invoke(null, protocols.get("play"), "existing", "replacement"));
        assertInstanceOf(UnsupportedOperationException.class, error.getCause());
        assertEquals("codec", protocols.get("play").get("existing"));
    }
}
