package qouteall.imm_ptl.core.collision;

/**
 * Detects chained collision wrappers that receive the exact same invocation
 * twice. When several mixins wrap the same {@code Entity.move} collide call
 * (for example the base portal wrapper plus a mod-specific compat wrapper),
 * every wrapper in the chain calls the shared hook with identical arguments.
 *
 * <p>The guard lets the outermost call run the full portal logic and makes
 * inner chained repeats pass straight through, so exactly one effective
 * wrapper executes per movement regardless of how many stack up. Genuine
 * recursive movements are unaffected because they pass a different move
 * vector instance.
 */
public final class CollisionChainGuard {
    private static final ThreadLocal<Object[]> ACTIVE = new ThreadLocal<>();

    private CollisionChainGuard() {}

    /**
     * Returns true when the current thread is already inside
     * {@link #enter} for this exact owner and invocation payload,
     * meaning this call comes from a chained duplicate wrapper rather
     * than a fresh collision request.
     */
    public static boolean isChainedRepeat(Object owner, Object payload) {
        Object[] active = ACTIVE.get();
        return active != null && active[0] == owner && active[1] == payload;
    }

    /**
     * Marks the owner/payload pair as actively processed while
     * {@code task} runs, restoring any outer state afterwards.
     */
    public static <R> R enter(Object owner, Object payload, java.util.function.Supplier<R> task) {
        Object[] previous = ACTIVE.get();
        ACTIVE.set(new Object[] {owner, payload});
        try {
            return task.get();
        } finally {
            ACTIVE.set(previous);
        }
    }
}
