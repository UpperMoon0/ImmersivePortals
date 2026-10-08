package qouteall.imm_ptl.core.compat.mixin.fabric;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.FabricNativeRegistration;

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
        var factoryActive = new java.util.concurrent.atomic.AtomicBoolean();
        var setup = new java.util.concurrent.atomic.AtomicBoolean(true);
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
                                assertTrue(setup.get(), "previous factory must restore setup before releasing the lock");
                                setup.set(false);
                                try { register.invoke(null, protocols.get(protocol), key, key); }
                                catch (Exception error) { throw new AssertionError(error); }
                                finally { factoryActive.set(false); }
                                return key;
                            };
                            assertEquals(id, FabricNativeRegistration.register(handlers.get(protocol), id, factory, setup::get, setup::set));
                            // A repeated receiver must reuse its handler without registering a duplicate payload.
                            assertEquals(id, FabricNativeRegistration.register(handlers.get(protocol), id, factory, setup::get, setup::set));
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

    @Test
    void failingNativeRegistrationRestoresCompletedSetupAndDoesNotCacheHandler() {
        verifyFailedFactoryRestoresSetup(true);
    }

    @Test
    void failingNativeRegistrationPreservesAnOpenRegistrationPhase() {
        verifyFailedFactoryRestoresSetup(false);
    }

    private void verifyFailedFactoryRestoresSetup(boolean originalSetup) {
        var setup = new java.util.concurrent.atomic.AtomicBoolean(originalSetup);
        Map<Object, Object> handlers = new HashMap<>();
        var duplicate = new UnsupportedOperationException("duplicate payload");
        assertSame(duplicate, assertThrows(UnsupportedOperationException.class,
            () -> FabricNativeRegistration.register(handlers, "payload", id -> {
                setup.set(false); // Upstream changes this before NetworkRegistry.register().
                throw duplicate;
            }, setup::get, setup::set)));
        assertEquals(originalSetup, setup.get());
        assertFalse(handlers.containsKey("payload"));
        // A later factory must see the original phase and still be able to register normally.
        assertEquals("handler", FabricNativeRegistration.register(handlers, "payload", id -> {
            assertEquals(originalSetup, setup.get());
            setup.set(false);
            return "handler";
        }, setup::get, setup::set));
        assertEquals(originalSetup, setup.get());
    }
}
