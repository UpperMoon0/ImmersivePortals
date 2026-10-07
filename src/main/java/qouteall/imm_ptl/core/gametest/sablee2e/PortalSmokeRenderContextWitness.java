package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Read-only witness around complete GameRenderer views, including deferred Iris portals. */
public final class PortalSmokeRenderContextWitness {
    public static final PortalSmokeRenderContextWitness LIVE = new PortalSmokeRenderContextWitness();

    private View current;
    private long observedViews, sameRendererViews, nullContexts, nonNullContexts, fallbackScopes;

    public View enter(Object renderer, Supplier<?> context, Supplier<?> fallback) {
        return enter(() -> renderer, context, fallback);
    }

    public View enter(Supplier<?> renderer, Supplier<?> context, Supplier<?> fallback) {
        View view = new View(Objects.requireNonNull(renderer.get()), renderer, context, fallback);
        current = view;
        observedViews++;
        return view;
    }

    public Map<String, Object> snapshot() {
        int openViews = 0;
        for (View view = current; view != null; view = view.parent) openViews++;
        return Map.of("observedViews", observedViews, "openViews", openViews,
            "sameRendererViewsVerified", sameRendererViews, "nullContextsVerified", nullContexts,
            "nonNullContextsVerified", nonNullContexts, "fallbackScopesVerified", fallbackScopes);
    }

    public static Object readContext(Object renderer) {
        try {
            return renderer.getClass().getMethod("ip_getFlywheelRenderContext").invoke(renderer);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot observe the live Flywheel context", error);
        }
    }

    public final class View implements AutoCloseable {
        private final View parent = current;
        private final Object renderer;
        private final Supplier<?> currentRenderer;
        private final Supplier<?> context;
        private final Supplier<?> fallback;
        private final Object previousContext;
        private final Object previousFallback;
        private final boolean sameRenderer;

        private View(Object renderer, Supplier<?> currentRenderer, Supplier<?> context, Supplier<?> fallback) {
            this.renderer = renderer;
            this.currentRenderer = currentRenderer;
            this.context = context;
            this.fallback = fallback;
            previousContext = context.get();
            previousFallback = fallback.get();
            boolean found = false;
            for (View view = parent; view != null; view = view.parent) {
                if (view.renderer == renderer) found = true;
            }
            sameRenderer = found;
        }

        @Override
        public void close() {
            if (current != this) throw new IllegalStateException("Flywheel view witnesses must close in reverse order");
            try {
                // Observe only: a test must not repair the production restoration it verifies.
                if (currentRenderer.get() != renderer) {
                    throw new IllegalStateException("Nested view did not restore its actual LevelRenderer by identity");
                }
                if (context.get() != previousContext) {
                    throw new IllegalStateException("Nested view did not restore its actual Flywheel context by identity");
                }
                if (fallback.get() != previousFallback) {
                    throw new IllegalStateException("Nested view did not restore its Flywheel fallback scope");
                }
                if (sameRenderer) {
                    sameRendererViews++;
                    if (previousContext == null) nullContexts++;
                    else nonNullContexts++;
                    fallbackScopes++;
                }
            } finally {
                current = parent;
            }
        }
    }
}
