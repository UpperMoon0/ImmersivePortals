package qouteall.imm_ptl.core.compat.sable;

import java.util.Objects;
import java.util.function.Predicate;

/** One publishing thread, arbitrary readers; each publication has a distinct lifetime. */
public final class SableRenderOwnerEpoch<T> {
    private record Generation<T>(T value) {}
    private volatile Generation<T> current;
    private boolean closed;

    public boolean isClosed() { return closed; }
    public void invalidate() { current=null; }
    public void close() { closed=true; current=null; }
    public void publish(T value) {
        if (!closed) current=new Generation<>(Objects.requireNonNull(value));
    }

    public boolean test(Predicate<T> predicate) {
        var observed=current;
        return observed!=null && predicate.test(observed.value()) && current==observed;
    }
}
