package qouteall.imm_ptl.core.collision;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that chained duplicate collision wrappers (several mixins wrapping
 * the same Entity.move collide invocation) execute the expensive hook exactly
 * once, while genuine recursive movements still get full processing.
 */
class CollisionChainGuardTest {

    @Test
    void outermostCallRunsFullLogic() {
        AtomicInteger executions = new AtomicInteger();
        String result = simulateWrappedCall(new Object(), "move-1", executions);
        assertEquals("full", result);
        assertEquals(1, executions.get());
    }

    @Test
    void chainedDuplicateWrapperPassesThrough() {
        // Mirrors the real MixinExtras chain: the high-priority Sable compat
        // wrapper receives Entity.move's collide invocation first and treats
        // the low-priority base wrapper as its "vanilla collision" operation.
        // Both wrappers feed the same shared hook with identical arguments.
        Object entity = new Object();
        Object move = new Object();

        AtomicInteger expensiveRuns = new AtomicInteger();

        class Wrappers {
            String hook(Supplier<String> vanillaCollision) {
                if (CollisionChainGuard.isChainedRepeat(entity, move)) {
                    return vanillaCollision.get();
                }
                return CollisionChainGuard.enter(entity, move, () -> {
                    expensiveRuns.incrementAndGet();
                    return vanillaCollision.get();
                });
            }

            String baseWrapper() {
                return this.hook(() -> "real-vanilla-collide");
            }

            String sableCompatWrapper() {
                return this.hook(this::baseWrapper);
            }
        }

        assertEquals("real-vanilla-collide", new Wrappers().sableCompatWrapper());
        assertEquals(1, expensiveRuns.get(), "expensive logic must run exactly once");
    }

    @Test
    void recursiveMovementWithDifferentVectorIsFullyProcessed() {
        AtomicInteger executions = new AtomicInteger();

        class Recursion {
            String handle(Object move) {
                if (CollisionChainGuard.isChainedRepeat(this, move)) {
                    return "passthrough";
                }
                return CollisionChainGuard.enter(this, move, () -> {
                    executions.incrementAndGet();
                    if (move == "first") {
                        return this.handle("second");
                    }
                    return "done:" + move;
                });
            }
        }

        assertEquals("done:second", new Recursion().handle("first"));
        assertEquals(2, executions.get());
    }

    @Test
    void guardStateDoesNotLeakAcrossInvocations() {
        Object owner = new Object();
        assertFalse(CollisionChainGuard.isChainedRepeat(owner, new Object()));
        CollisionChainGuard.enter(owner, "payload", () -> {
            assertTrue(CollisionChainGuard.isChainedRepeat(owner, "payload"));
            return null;
        });
        assertFalse(CollisionChainGuard.isChainedRepeat(owner, "payload"));
    }

    private String simulateWrappedCall(Object entity, Object move, AtomicInteger executions) {
        if (CollisionChainGuard.isChainedRepeat(entity, move)) {
            return "passthrough";
        }
        return CollisionChainGuard.enter(entity, move, () -> {
            executions.incrementAndGet();
            return "full";
        });
    }
}
