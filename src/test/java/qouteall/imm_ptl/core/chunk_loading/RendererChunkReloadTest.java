package qouteall.imm_ptl.core.chunk_loading;

import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise each real backend's event coalescing across a renderer replacement. */
class RendererChunkReloadTest {
    @Test
    void sodiumReloadDoesNotLoseChunksWhoseLightReturns() throws Exception {
        check("sodium", "net.caffeinemc.mods.sodium.client", true);
    }

    @Test
    void embeddiumReloadDoesNotLoseChunksWhoseLightReturns() throws Exception {
        check("embeddium", "org.embeddedt.embeddium.impl", true);
    }

    @Test
    void sodiumReloadDoesNotRetainChunksWhoseLightDisappears() throws Exception {
        check("sodium", "net.caffeinemc.mods.sodium.client", false);
    }

    @Test
    void embeddiumReloadDoesNotRetainChunksWhoseLightDisappears() throws Exception {
        check("embeddium", "org.embeddedt.embeddium.impl", false);
    }

    private static void check(String backend, String packageName, boolean regainsLight) throws Exception {
        assertNotEquals(regainsLight, simulate(backend, packageName, regainsLight, false),
            "Control must reproduce the stale event baseline");
        assertEquals(regainsLight, simulate(backend, packageName, regainsLight, true),
            "Replacement sections must follow the actual ready set");
    }

    private static boolean simulate(String backend, String packageName, boolean regainsLight, boolean rebase)
        throws Exception {
        Class<?> type = Class.forName(packageName + ".render.chunk.map.ChunkTracker");
        Object tracker = type.getConstructor().newInstance();
        Class<?> status = Class.forName(packageName + ".render.chunk.map.ChunkStatus");
        int blocks = status.getField("FLAG_HAS_BLOCK_DATA").getInt(null);
        int light = status.getField("FLAG_HAS_LIGHT_DATA").getInt(null);
        Method added = type.getMethod("onChunkStatusAdded", int.class, int.class, int.class);
        Method removed = type.getMethod("onChunkStatusRemoved", int.class, int.class, int.class);
        Method events = java.util.Arrays.stream(type.getMethods())
            .filter(method -> method.getName().equals("forEachEvent")).findFirst().orElseThrow();

        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            added.invoke(tracker, x, z, blocks | light);

        if (regainsLight) {
            // The old renderer already owns the center. A neighbor loses light
            // while this remote dimension is not being rendered.
            drain(tracker, events, new HashSet<>());
            removed.invoke(tracker, 1, 1, light);
        }

        LongCollection ready;
        if (rebase) {
            String mixin = "qouteall.imm_ptl.core.compat.mixin." + backend + ".Mixin"
                + (backend.equals("sodium") ? "Sodium" : "Embeddium") + "WorldRenderer";
            Method reset = Class.forName(mixin).getDeclaredMethod("ip_rebaseChunkEventsForReload", type);
            reset.setAccessible(true);
            ready = (LongCollection) reset.invoke(null, tracker);
        } else {
            ready = (LongCollection) type.getMethod("getReadyChunks").invoke(tracker);
        }
        // The new manager is initialized from precisely the backend's ready snapshot.
        Set<Long> replacementSections = new HashSet<>();
        for (long chunk : ready) replacementSections.add(chunk);

        if (regainsLight) added.invoke(tracker, 1, 1, light);
        else removed.invoke(tracker, 1, 1, light);
        drain(tracker, events, replacementSections);
        return replacementSections.contains(ChunkPos.asLong(0, 0));
    }

    private static void drain(Object tracker, Method events, Set<Long> sections) throws Exception {
        Class<?> handler = events.getParameterTypes()[0];
        Object load = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[]{handler},
            (proxy, method, args) -> {
                sections.add(ChunkPos.asLong((int) args[0], (int) args[1]));
                return null;
            });
        Object unload = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[]{handler},
            (proxy, method, args) -> {
                sections.remove(ChunkPos.asLong((int) args[0], (int) args[1]));
                return null;
            });
        events.invoke(tracker, load, unload);
    }
}
