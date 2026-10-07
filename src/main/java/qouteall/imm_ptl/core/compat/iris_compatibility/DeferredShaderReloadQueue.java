package qouteall.imm_ptl.core.compat.iris_compatibility;

import java.util.IdentityHashMap;
import java.util.Map;

/** Client-thread requests coalesced by renderer identity, outside recursive rendering. */
final class DeferredShaderReloadQueue {
    private final Map<Object, Runnable> pending = new IdentityHashMap<>();
    private boolean draining;

    void request(Object renderer, Runnable reload) {
        // beginLevelRendering has newer block mappings than preparePipeline.
        pending.put(renderer, reload);
    }

    void drain(boolean rendering) {
        if (rendering || draining || pending.isEmpty()) return;
        var batch = new IdentityHashMap<>(pending);
        pending.clear();
        draining = true;
        try {
            batch.values().forEach(Runnable::run);
        }
        finally {
            draining = false;
        }
    }

    void clear() {
        pending.clear();
    }
}
