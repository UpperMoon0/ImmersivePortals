package qouteall.imm_ptl.core.compat;

import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.core.compat.flywheel.FlywheelRenderScope;

import static org.junit.jupiter.api.Assertions.*;

class FlywheelRenderScopeTest {
    @Test
    void crossingEntityKeepsWholeViewOnVanillaAfterTemporaryClipEnds() {
        assertFalse(FlywheelRenderScope.isFallbackActive());
        try (var crossingView = FlywheelRenderScope.enter(true)) {
            assertTrue(FlywheelRenderScope.isFallbackActive(), "Disable visualization before the frame plan begins");
            boolean temporaryEntityClip = true;
            assertTrue(FlywheelRenderScope.isFallbackActive() || temporaryEntityClip);
            temporaryEntityClip = false;
            assertTrue(FlywheelRenderScope.isFallbackActive() || temporaryEntityClip,
                "afterEntities must still suppress the whole unclipped GPU visual");
        }
        assertFalse(FlywheelRenderScope.isFallbackActive(), "The next unrelated view keeps its selected backend");
    }

    @Test
    void nestedAndRemoteViewsRestoreTheirOwnDecisionEvenWhenTheyThrow() {
        try (var outer = FlywheelRenderScope.enter(true)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var otherLevel = FlywheelRenderScope.enter(false)) {
                    assertFalse(FlywheelRenderScope.isFallbackActive());
                    try (var portal = FlywheelRenderScope.enter(true)) {
                        assertTrue(FlywheelRenderScope.isFallbackActive());
                    }
                    assertFalse(FlywheelRenderScope.isFallbackActive());
                    throw new IllegalStateException("render failed");
                }
            });
            assertTrue(FlywheelRenderScope.isFallbackActive());
        }
        assertFalse(FlywheelRenderScope.isFallbackActive());
    }

    @Test
    void chunkWorkersDoNotInheritTheCurrentViewFallback() throws Exception {
        try (var view = FlywheelRenderScope.enter(true)) {
            boolean[] workerFallback = {true};
            Thread worker = new Thread(() -> workerFallback[0] = FlywheelRenderScope.isFallbackActive());
            worker.start();
            worker.join();
            assertFalse(workerFallback[0]);
            assertTrue(FlywheelRenderScope.isFallbackActive());
        }
    }

    @Test
    void workersUsePhysicalPlayerLevelWhileRenderingKeepsActualViewLevel() {
        Object mainLevel = new Object();
        Object remoteView = new Object();
        assertSame(remoteView, FlywheelRenderScope.visualizationLevel(true, remoteView, mainLevel));
        assertSame(mainLevel, FlywheelRenderScope.visualizationLevel(false, remoteView, mainLevel));
        assertSame(mainLevel, FlywheelRenderScope.visualizationLevel(false, mainLevel, mainLevel));
    }

    @Test
    void nullPlayerStartupPreservesOriginalLevelIdentity() {
        Object startupLevel = new Object();
        assertSame(startupLevel, FlywheelRenderScope.visualizationLevel(false, startupLevel, null));
        assertSame(startupLevel, FlywheelRenderScope.visualizationLevel(true, startupLevel, null));
        assertNull(FlywheelRenderScope.visualizationLevel(false, null, null));
        assertNull(FlywheelRenderScope.visualizationLevel(false, null, startupLevel),
            "A lingering player during disconnect must not resurrect visualization for a torn-down world");
    }

}
