package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SableFixtureTimingTest {
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/qouteall/imm_ptl/core/gametest/sablee2e/" + name + ".java"));
    }

    @Test
    void earlyClientReceiptWaitsForServerThresholdWithoutFailing() {
        assertFalse(SableRemoteObserverReadiness.ready(true, 0.1998));
        assertFalse(SableRemoteObserverReadiness.ready(true, Math.nextDown(0.25)));
        assertTrue(SableRemoteObserverReadiness.ready(true, 0.25));
        assertTrue(SableRemoteObserverReadiness.ready(true, 0.30));
    }

    @Test
    void serverMovementCannotReplaceClientEvidence() {
        assertFalse(SableRemoteObserverReadiness.ready(false, 0.25));
        assertFalse(SableRemoteObserverReadiness.ready(false, 2.0));
    }

    @Test
    void nonFiniteDisplacementCannotPass() {
        assertFalse(SableRemoteObserverReadiness.ready(true, Double.NaN));
        assertFalse(SableRemoteObserverReadiness.ready(true, Double.POSITIVE_INFINITY));
        assertFalse(SableRemoteObserverReadiness.ready(true, Double.NEGATIVE_INFINITY));
    }

    @Test
    void resetIsPublishedAfterFlightProofButBeforeRemovalAndTeleport() throws Exception {
        String server = source("SableStandingBalloonServer");
        String observe = server.substring(server.indexOf("private static void observe()"), server.indexOf("private static Object burner("));
        int samples = observe.indexOf("if(settledSamples<80) return;");
        int flight = observe.indexOf("require(body.logicalPose().position().y-arrivalY>0.5,");
        int burner = observe.indexOf("require((boolean)burner.getClass().getMethod(\"canOutputGas\")");
        int receipt = observe.indexOf("receipts.add(");
        int marker = observe.indexOf("PortalSmokeSupport.write(\"balloon-reset.txt\"");
        int removal = observe.indexOf(".removeSubLevel(");
        int teleport = observe.indexOf(".forceTeleportPlayer(");
        assertTrue(samples >= 0 && flight > samples && burner > flight && receipt > burner);
        assertTrue(marker > receipt && removal > marker && teleport > removal,
            "Successful case teardown must be announced before its first network-visible mutation");
        assertEquals(marker, observe.lastIndexOf("PortalSmokeSupport.write(\"balloon-reset.txt\""));
        assertTrue(source("SableStandingBalloonClient").contains("if(++stable>=40)"));
        assertTrue(source("PortalSmokeSupport").contains("StandardCopyOption.ATOMIC_MOVE"));
    }

    @Test
    void remoteObserverJoinsEvidenceBeforeStoppingAndRetainsBoundsAndFinalConfirmation() throws Exception {
        String server = source("SableDimensionStackDedicatedServerTest");
        String observer = server.substring(server.indexOf("private static void verifyRemoteObserverOrWait()"), server.indexOf("private static boolean hasCommandForcedTicket("));
        int displacement = observer.indexOf("double moved = Math.abs(body.logicalPose().position().x() - remoteHeldPosition.x);");
        int finite = observer.indexOf("require(Double.isFinite(moved),");
        int gate = observer.indexOf("if (!SableRemoteObserverReadiness.ready(");
        int receipt = observer.indexOf("SableDimensionStackIntegrationMarkers.exists(\"client-remote-moved.txt\"), moved", gate);
        int wait = observer.indexOf(")) return;", receipt);
        int threshold = observer.indexOf("require(moved >= 0.25,", wait);
        int stop = observer.indexOf("setLinearVelocity(handle, new Vector3d());", threshold);
        int pass = observer.indexOf("SableDimensionStackIntegrationMarkers.serverPass(", stop);
        assertTrue(displacement >= 0 && finite > displacement && gate > finite && receipt > gate);
        assertTrue(wait > receipt && threshold > wait && stop > threshold && pass > stop);
        assertFalse(observer.contains("ticks ="), "Waiting must not restart the global timeout");
        assertTrue(server.contains("TIMEOUT_TICKS = 1200;"));
        assertTrue(server.contains("if (ticks > TIMEOUT_TICKS)"));
        String client = source("SableDimensionStackDedicatedClientTest");
        assertTrue(client.contains("TIMEOUT_TICKS = 1200;"));
        assertTrue(client.contains("if (ticks > TIMEOUT_TICKS)"));
        assertTrue(client.contains("MIN_REMOTE_OBSERVED_MOVEMENT = 0.30;"));
        assertTrue(client.contains("if (!SableDimensionStackIntegrationMarkers.exists(\"server-pass.txt\")) return;"));
        assertTrue(client.contains("require(Math.abs(remote.logicalPose().position().x() - remoteSourceX) >= MIN_REMOTE_OBSERVED_MOVEMENT,"));
    }
}
