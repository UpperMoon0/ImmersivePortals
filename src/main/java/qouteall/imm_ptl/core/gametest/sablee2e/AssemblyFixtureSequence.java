package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.ryanhcode.sable.neoforge.gametest.AssemblyTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;

/** Serialize fixtures sharing one position, including their deferred assembly assertions. */
public final class AssemblyFixtureSequence {
    private static final ThreadLocal<AssemblyFixtureSequence> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Long> MINIMUM_DELAY = ThreadLocal.withInitial(() -> 1L);

    private final GameTestSequence sequence;
    private final long minimumDelay;
    private long lastScheduledTick;
    private boolean waitingForAssembly;

    public AssemblyFixtureSequence(GameTestHelper helper) {
        sequence = helper.startSequence();
        minimumDelay = MINIMUM_DELAY.get();
    }

    public void schedule(long scheduledTick, Runnable fixture) {
        int spacing = Math.toIntExact(scheduledTick - lastScheduledTick);
        lastScheduledTick = scheduledTick;
        sequence.thenExecuteAfter(spacing, () -> {
            ACTIVE.set(this);
            try {
                fixture.run();
            } finally {
                ACTIVE.remove();
            }
        }).thenWaitUntil(() -> {
            if (waitingForAssembly) {
                throw new GameTestAssertException("Waiting for the previous block's assembly assertions");
            }
        });
    }

    public static void scheduleAssembly(GameTestHelper helper, long delay, Runnable assembly) {
        AssemblyFixtureSequence fixture = ACTIVE.get();
        if (fixture == null) {
            throw new IllegalStateException("Assembly callback has no fixture sequence");
        }
        fixture.waitingForAssembly = true;
        helper.runAfterDelay(Math.max(delay, fixture.minimumDelay), () -> {
            try {
                assembly.run();
            } finally {
                fixture.waitingForAssembly = false;
            }
        });
    }

    /** Exercise the real upstream assertions with a delay longer than its two-tick spacing. */
    public static void testDelayedAssembly(GameTestHelper helper) {
        MINIMUM_DELAY.set(3L);
        try {
            AssemblyTest.testAllBlocks(helper);
        } finally {
            MINIMUM_DELAY.remove();
        }
    }
}
