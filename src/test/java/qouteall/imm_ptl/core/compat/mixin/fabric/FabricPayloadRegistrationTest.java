package qouteall.imm_ptl.core.compat.mixin.fabric;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FabricPayloadRegistrationTest {
    private final Field field;
    private final Method initialize;
    private final Method register;

    FabricPayloadRegistrationTest() throws ReflectiveOperationException {
        field = MixinFabricPayloadTypeRegistry.class.getDeclaredField("packetTypes");
        initialize = MixinFabricPayloadTypeRegistry.class.getDeclaredMethod(
            "ip$makeRegistryConcurrent", CallbackInfo.class);
        register = MixinFabricPayloadTypeRegistry.class.getDeclaredMethod(
            "ip$registerAtomically", Map.class, Object.class, Object.class);
        field.setAccessible(true);
        initialize.setAccessible(true);
        register.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> registry(MixinFabricPayloadTypeRegistry mixin) throws Exception {
        field.set(mixin, new HashMap<>(Map.of("existing", "codec")));
        initialize.invoke(mixin, new CallbackInfo("<init>", false));
        Map<Object, Object> result = (Map<Object, Object>) field.get(mixin);
        assertInstanceOf(ConcurrentHashMap.class, result);
        assertEquals("codec", result.get("existing"));
        return result;
    }

    @Test
    void parallelModConstructorsKeepEveryPayloadVisible() throws Exception {
        var mixin = new MixinFabricPayloadTypeRegistry();
        var types = registry(mixin);
        var barrier = new CyclicBarrier(16);
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 16; worker++) {
                final int slot = worker;
                tasks.add(pool.submit(() -> {
                    barrier.await();
                    for (int i = 0; i < 2000; i++) {
                        String id = slot + ":" + i;
                        register.invoke(mixin, types, id, id);
                        assertEquals(id, types.get(id), "payload must be visible to its receiver immediately");
                    }
                    return null;
                }));
            }
            for (var task : tasks) task.get();
        }
        assertEquals(32001, types.size());
    }

    @Test
    void racingDuplicateCannotReplaceTheWinningCodec() throws Exception {
        var mixin = new MixinFabricPayloadTypeRegistry();
        var types = registry(mixin);
        var barrier = new CyclicBarrier(16);
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 16; worker++) {
                final int codec = worker;
                tasks.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        register.invoke(mixin, types, "same-payload", codec);
                        return true;
                    } catch (InvocationTargetException exception) {
                        assertInstanceOf(IllegalArgumentException.class, exception.getCause());
                        return false;
                    }
                }));
            }
            int winners = 0;
            for (var task : tasks) if (task.get()) winners++;
            assertEquals(1, winners);
        }
        Object winner = types.get("same-payload");
        assertNotNull(winner);
        var error = assertThrows(InvocationTargetException.class,
            () -> register.invoke(mixin, types, "same-payload", "replacement"));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertEquals(winner, types.get("same-payload"));
    }
}
