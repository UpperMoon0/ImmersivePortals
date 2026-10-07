package qouteall.imm_ptl.core.compat.iris_compatibility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeferredShaderReloadQueueTest {
    @Test
    void materialAndBlockMappingRequestsCoalesceToLatestSettingsAndRunOnlyOutsideRendering() {
        var queue = new DeferredShaderReloadQueue();
        Object renderer = new Object();
        var calls = new ArrayList<String>();
        queue.request(renderer, () -> calls.add("constructor settings"));
        queue.request(renderer, () -> calls.add("initialized block mappings"));
        queue.drain(true);
        assertTrue(calls.isEmpty());
        queue.drain(false);
        queue.drain(false);
        assertEquals(List.of("initialized block mappings"), calls);
    }

    @Test
    void rendererIdentityDoesNotMergeDifferentWorlds() {
        var queue = new DeferredShaderReloadQueue();
        // Equal objects deliberately represent different renderers.
        Object nether = new String("renderer");
        Object end = new String("renderer");
        var calls = new ArrayList<String>();
        queue.request(nether, () -> calls.add("nether"));
        queue.request(end, () -> calls.add("end"));
        queue.drain(false);
        assertEquals(2, calls.size());
        assertTrue(calls.containsAll(List.of("nether", "end")));
    }

    @Test
    void reloadCannotRecursivelyDrainNewRequestsInTheSameFrame() {
        var queue = new DeferredShaderReloadQueue();
        Object renderer = new Object();
        var calls = new ArrayList<String>();
        queue.request(renderer, () -> {
            calls.add("first");
            queue.request(renderer, () -> calls.add("next frame"));
            queue.drain(false);
        });
        queue.drain(false);
        assertEquals(List.of("first"), calls);
        queue.drain(false);
        assertEquals(List.of("first", "next frame"), calls);
    }

    @Test
    void pipelineDestructionOrDisconnectDiscardsOutstandingRequests() {
        var queue = new DeferredShaderReloadQueue();
        queue.request(new Object(), () -> fail("stale pipeline must not be replayed"));
        queue.clear();
        queue.drain(false);
    }

    @Test
    void failureDoesNotLeaveDrainPermanentlyLocked() {
        var queue = new DeferredShaderReloadQueue();
        queue.request(new Object(), () -> { throw new IllegalStateException("failed reload"); });
        assertThrows(IllegalStateException.class, () -> queue.drain(false));
        var calls = new ArrayList<String>();
        queue.request(new Object(), () -> calls.add("new pipeline"));
        queue.drain(false);
        assertEquals(List.of("new pipeline"), calls);
    }
}
