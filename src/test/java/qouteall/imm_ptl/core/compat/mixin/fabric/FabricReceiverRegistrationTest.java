package qouteall.imm_ptl.core.compat.mixin.fabric;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.apache.commons.lang3.function.TriConsumer;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.sinytra.fabric.networking_api.NeoNetworkRegistrar;
import qouteall.imm_ptl.core.compat.FabricReceiverRegistration;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class FabricReceiverRegistrationTest {
    record Payload() implements CustomPacketPayload {
        @Override public Type<? extends CustomPacketPayload> type() {
            return new Type<>(ResourceLocation.fromNamespaceAndPath("imm_ptl", "same_payload"));
        }
    }

    static final class Fixture {
        final NeoNetworkRegistrar.NeoPayloadHandler<Payload> target = new NeoNetworkRegistrar.NeoPayloadHandler<>();
        final MixinFabricNeoPayloadHandler mixin = new MixinFabricNeoPayloadHandler();
        final Method global = method("ip$registerGlobal", PacketFlow.class);
        final Method local = method("ip$registerLocal", ICommonPacketListener.class);

        Fixture() throws Exception {
            for (String name : List.of("globalReceivers", "localReceivers")) {
                Field real = target.getClass().getDeclaredField(name), shadow = mixin.getClass().getDeclaredField(name);
                real.setAccessible(true); shadow.setAccessible(true);
                shadow.set(mixin, real.get(target));
            }
            var init = mixin.getClass().getDeclaredMethod("ip$publishReceiversSafely", CallbackInfo.class);
            init.setAccessible(true); init.invoke(mixin, new CallbackInfo("<init>", false));
            for (String name : List.of("globalReceivers", "localReceivers")) {
                Field real = target.getClass().getDeclaredField(name), shadow = mixin.getClass().getDeclaredField(name);
                real.setAccessible(true); shadow.setAccessible(true);
                assertInstanceOf(ConcurrentHashMap.class, shadow.get(mixin));
                real.set(target, shadow.get(mixin));
            }
        }

        static Method method(String name, Class<?> key) {
            try {
                var method = MixinFabricNeoPayloadHandler.class.getDeclaredMethod(name, key, Object.class,
                    Function.class, TriConsumer.class, Operation.class);
                method.setAccessible(true); return method;
            } catch (Exception e) { throw new AssertionError(e); }
        }

        boolean register(Object key, AtomicInteger callback) throws Exception {
            Function<IPayloadContext, IPayloadContext> factory = Function.identity();
            TriConsumer<AtomicInteger, Payload, IPayloadContext> consumer = (count, payload, context) -> count.incrementAndGet();
            Operation<Boolean> original = ignored -> key instanceof PacketFlow flow
                ? target.registerGlobalHandler(flow, callback, factory, consumer)
                : target.registerLocalReceiver((ICommonPacketListener) key, callback, factory, consumer);
            return (boolean) (key instanceof PacketFlow ? global : local).invoke(mixin, key, callback, factory, consumer, original);
        }

        void dispatch(PacketFlow flow, ICommonPacketListener listener) {
            IPayloadContext context = (IPayloadContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{IPayloadContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "flow" -> flow;
                    case "listener" -> listener;
                    case "enqueueWork" -> { ((Runnable) args[0]).run(); yield CompletableFuture.completedFuture(null); }
                    default -> throw new AssertionError(method.getName());
                });
            target.handle(new Payload(), context);
        }
    }

    static ICommonPacketListener listener() {
        return (ICommonPacketListener) Proxy.newProxyInstance(FabricReceiverRegistrationTest.class.getClassLoader(),
            new Class[]{ICommonPacketListener.class}, (proxy, method, args) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "listener";
                default -> throw new AssertionError(method.getName());
            });
    }

    @Test void bothFlowsForTheSamePayloadRemainVisibleAndDispatchExactlyOnce() throws Exception {
        var fixture = new Fixture();
        var barrier = new CyclicBarrier(2);
        var counts = List.of(new AtomicInteger(), new AtomicInteger());
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> tasks = new ArrayList<>();
            int index = 0;
            for (PacketFlow flow : PacketFlow.values()) {
                var callback = counts.get(index++);
                tasks.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    assertTrue(fixture.register(flow, callback));
                    assertTrue(fixture.target.hasGlobalHandler(flow));
                    fixture.dispatch(flow, listener());
                    return null;
                }));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        counts.forEach(count -> assertEquals(1, count.get()));
    }

    @Test void duplicateFlowOrListenerHasOneWinnerAndReplacementRequiresUnregistration() throws Exception {
        for (Object key : List.of(PacketFlow.CLIENTBOUND, listener())) {
            var fixture = new Fixture();
            var barrier = new CyclicBarrier(16);
            var callbacks = new ArrayList<AtomicInteger>();
            int winners = 0;
            try (var pool = Executors.newFixedThreadPool(16)) {
                List<Future<Boolean>> tasks = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    var callback = new AtomicInteger(); callbacks.add(callback);
                    tasks.add(pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return fixture.register(key, callback); }));
                }
                for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) winners++;
            }
            assertEquals(1, winners);
            fixture.dispatch(PacketFlow.CLIENTBOUND, key instanceof ICommonPacketListener l ? l : listener());
            assertEquals(1, callbacks.stream().mapToInt(AtomicInteger::get).sum());
            AtomicInteger removed = key instanceof PacketFlow flow ? fixture.target.unregisterGlobalHandler(flow)
                : fixture.target.unregisterLocalHandler((ICommonPacketListener) key);
            assertEquals(1, removed.get());
            var replacement = new AtomicInteger();
            assertTrue(fixture.register(key, replacement));
            fixture.dispatch(PacketFlow.CLIENTBOUND, key instanceof ICommonPacketListener l ? l : listener());
            assertEquals(1, replacement.get());
        }
    }

    @Test void localRegistrationLookupDispatchAndUnregisterAreSafeAcrossDistinctListeners() throws Exception {
        var fixture = new Fixture();
        var barrier = new CyclicBarrier(16);
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 16; worker++) tasks.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                for (int i = 0; i < 200; i++) {
                    var listener = listener(); var count = new AtomicInteger();
                    assertTrue(fixture.register(listener, count));
                    assertTrue(fixture.target.hasLocalHandler(listener));
                    fixture.dispatch(PacketFlow.CLIENTBOUND, listener);
                    assertEquals(1, count.get());
                    assertSame(count, fixture.target.unregisterLocalHandler(listener));
                    assertFalse(fixture.target.hasLocalHandler(listener));
                }
                return null;
            }));
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
    }

    @Test void exceptionReleasesOnlyTheRegistrationLockAndVersionsAreStrictlyGated() {
        Map<Object, Object> map = new ConcurrentHashMap<>();
        assertThrows(IllegalStateException.class, () -> FabricReceiverRegistration.register(map, () -> { throw new IllegalStateException(); }));
        assertTrue(FabricReceiverRegistration.register(map, () -> true));
        assertDoesNotThrow(() -> FabricReceiverRegistration.checkVersion("4.2.2+a92978fd19"));
        for (String version : List.of("4.2.3", "4.2.2+unknown", "4.3.1", ""))
            assertThrows(IllegalStateException.class, () -> FabricReceiverRegistration.checkVersion(version));
    }
}
