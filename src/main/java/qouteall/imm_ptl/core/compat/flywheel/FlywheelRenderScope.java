package qouteall.imm_ptl.core.compat.flywheel;

/** One immutable fallback decision per world view, restored across recursion and exceptions. */
public final class FlywheelRenderScope implements AutoCloseable {
    private static final ThreadLocal<FlywheelRenderScope> CURRENT = new ThreadLocal<>();

    private final FlywheelRenderScope previous;
    private final boolean fallback;

    private FlywheelRenderScope(boolean fallback) {
        this.previous = CURRENT.get();
        this.fallback = fallback;
        CURRENT.set(this);
    }

    public static FlywheelRenderScope enter(boolean fallback) {
        return new FlywheelRenderScope(fallback);
    }

    public static boolean isFallbackActive() {
        FlywheelRenderScope scope = CURRENT.get();
        return scope != null && scope.fallback;
    }

    /** IP changes the rendered world, but never the physical player's world, during a portal view. */
    public static <T> T visualizationLevel(boolean renderThread, T viewLevel, T playerLevel) {
        return renderThread || viewLevel == null || playerLevel == null ? viewLevel : playerLevel;
    }

    @Override
    public void close() {
        if (CURRENT.get() != this) throw new IllegalStateException("Flywheel render scopes must close in reverse order");
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
