package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.compat.flywheel.FlywheelRenderScope;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PortalSmokeRenderContextWitnessTest {
    @Test
    void deferredSameRendererVerifiesNullContextAndScopeWithoutRepairingThem() {
        var witness = new PortalSmokeRenderContextWitness();
        Object renderer = new Object();
        var context = new AtomicReference<>();
        var fallback = new AtomicBoolean();
        try (var outer = witness.enter(renderer, context::get, fallback::get)) {
            // Iris finishes the outer LevelRenderer before entering another GameRenderer view.
            try (var nested = witness.enter(renderer, context::get, fallback::get)) {
                context.set(new Object());
                fallback.set(true);
                context.set(null);
                fallback.set(false);
            }
        }
        assertEquals(1L, witness.snapshot().get("sameRendererViewsVerified"));
        assertEquals(1L, witness.snapshot().get("nullContextsVerified"));
        assertEquals(0L, witness.snapshot().get("nonNullContextsVerified"));
        assertEquals(1L, witness.snapshot().get("fallbackScopesVerified"));
        assertEquals(0, witness.snapshot().get("openViews"));
    }

    @Test
    void overlappingRendererVerifiesTheExactLiveContextAcrossDifferentWorldAncestor() {
        var witness = new PortalSmokeRenderContextWitness();
        Object renderer = new Object();
        Object liveContext = new Object();
        var context = new AtomicReference<>(liveContext);
        try (var outer = witness.enter(renderer, context::get, () -> true)) {
            try (var otherWorld = witness.enter(new Object(), () -> null, () -> false)) {
                try (var nested = witness.enter(renderer, context::get, () -> true)) {
                    context.set(new Object());
                    context.set(liveContext);
                }
            }
        }
        assertEquals(1L, witness.snapshot().get("nonNullContextsVerified"));
        assertEquals(0L, witness.snapshot().get("nullContextsVerified"));
        assertEquals(1L, witness.snapshot().get("sameRendererViewsVerified"));
    }

    @Test
    void unrelatedRendererAndSequentialViewsDoNotProveSameRendererRecursion() {
        var witness = new PortalSmokeRenderContextWitness();
        Object renderer = new Object();
        try (var outer = witness.enter(renderer, () -> null, () -> false)) {
            try (var other = witness.enter(new Object(), () -> null, () -> false)) {}
        }
        try (var sequential = witness.enter(renderer, () -> null, () -> false)) {}
        assertEquals(3L, witness.snapshot().get("observedViews"));
        assertEquals(0L, witness.snapshot().get("sameRendererViewsVerified"));
    }

    @Test
    void failedRestorationIsObservedWithoutRepairOrCredit() {
        var witness = new PortalSmokeRenderContextWitness();
        Object renderer = new Object();
        var context = new AtomicReference<>();
        Object leaked = new Object();
        try (var outer = witness.enter(renderer, context::get, () -> false)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var nested = witness.enter(renderer, context::get, () -> false)) {
                    context.set(leaked);
                }
            });
            assertSame(leaked, context.get(), "The witness must never repair the state being tested");
            assertEquals(0L, witness.snapshot().get("sameRendererViewsVerified"));
            context.set(null);
        }
        assertEquals(0, witness.snapshot().get("openViews"));
    }

    @Test
    void valueEqualityCannotSubstituteForContextIdentity() {
        var witness = new PortalSmokeRenderContextWitness();
        var context = new AtomicReference<>(new String("context"));
        var view = witness.enter(new Object(), context::get, () -> false);
        context.set(new String("context"));
        assertThrows(IllegalStateException.class, view::close);
        assertEquals(0, witness.snapshot().get("openViews"));
    }

    @Test
    void readingTheCapturedContextCannotHideTheWrongActiveRenderer() {
        var witness = new PortalSmokeRenderContextWitness();
        Object expected = new Object();
        var renderer = new AtomicReference<>(expected);
        var view = witness.enter(renderer::get, () -> null, () -> null);
        Object leaked = new Object();
        renderer.set(leaked);
        assertThrows(IllegalStateException.class, view::close);
        assertSame(leaked, renderer.get());
        assertEquals(0, witness.snapshot().get("openViews"));
    }

    @Test
    void fallbackLeakCannotPassEvenWhenNullContextWasRestored() {
        var witness = new PortalSmokeRenderContextWitness();
        Object renderer = new Object();
        var fallback = new AtomicBoolean();
        try (var outer = witness.enter(renderer, () -> null, fallback::get)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var nested = witness.enter(renderer, () -> null, fallback::get)) {
                    fallback.set(true);
                }
            });
            assertTrue(fallback.get());
            assertEquals(0L, witness.snapshot().get("sameRendererViewsVerified"));
            fallback.set(false);
        }
    }

    @Test
    void equalFallbackFlagsCannotHideAChangedParentScope() {
        var witness = new PortalSmokeRenderContextWitness();
        try (var outerScope = FlywheelRenderScope.enter(true)) {
            var view = witness.enter(new Object(), () -> null, FlywheelRenderScope::currentScope);
            try (var leakedScope = FlywheelRenderScope.enter(true)) {
                assertThrows(IllegalStateException.class, view::close,
                    "Equal fallback flags must not hide a different scope object");
                assertSame(leakedScope, FlywheelRenderScope.currentScope(), "The witness does not repair the scope");
            }
            assertSame(outerScope, FlywheelRenderScope.currentScope());
        }
    }

    @Test
    void exceptionalExitStillChecksRestorationAndUnwindsWitnessStack() {
        var witness = new PortalSmokeRenderContextWitness();
        Object renderer = new Object();
        try (var outer = witness.enter(renderer, () -> null, () -> false)) {
            var original = new IllegalArgumentException("render failed");
            assertSame(original, assertThrows(IllegalArgumentException.class, () -> {
                try (var nested = witness.enter(renderer, () -> null, () -> false)) {
                    throw original;
                }
            }));
            assertEquals(1, witness.snapshot().get("openViews"));
        }
        assertEquals(0, witness.snapshot().get("openViews"));
        assertEquals(1L, witness.snapshot().get("nullContextsVerified"));
    }
}
