package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SableShipStagingAreaTest {
    @Test void supportIsOutsideTheClearedCorridorAndAllFiniteApertures() {
        for (int x=SableShipStagingArea.X-2;x<=SableShipStagingArea.X+2;x++)
            for (int z=SableShipStagingArea.Z-2;z<=SableShipStagingArea.Z+2;z++) {
                assertFalse(SableShipFlightCorridor.contains(x,SableShipStagingArea.Y,z));
                assertTrue(x>80 && z>80,"Outside both ordinary horizontal and broadside vertical apertures");
                assertTrue(SableShipStagingArea.Y>128+20,"Above prepared stack ceiling/braking volume");
            }
    }

    @Test void fullPlayerFootprintFitsOnRealSupport() {
        assertTrue(SableShipStagingArea.supportedPosition(112.5,200,112.5));
        assertFalse(SableShipStagingArea.supportedPosition(0,200,0),"Old staging fell through removed stack floor");
        assertFalse(SableShipStagingArea.supportedPosition(112.5,-461,112.5),"Observed void-death position cannot pass");
        assertFalse(SableShipStagingArea.supportedPosition(109.99,200,112.5));
        assertFalse(SableShipStagingArea.supportedPosition(112.5,200,115));
        assertFalse(SableShipStagingArea.supportedPosition(Double.NaN,200,112.5));
    }

    @Test void setupCannotReviveOrRemountADeadRiderAndCannotInterveneInFlight() throws Exception {
        String s=Files.readString(Path.of("src/main/java/qouteall/imm_ptl/core/gametest/sablee2e/SableShipFlightTest.java"));
        String staging=s.substring(s.indexOf("private static void parkPlayerForSetup()"),s.indexOf("private static void verifySetupStaging()"));
        assertTrue(staging.contains("phase!=Phase.FLY && phase!=Phase.CLEAR_SEAM"));
        assertTrue(staging.indexOf("requireOriginalPlayerAlive")<staging.indexOf("player.teleportTo"));
        assertTrue(staging.indexOf("player.fallDistance=0")<staging.indexOf("player.teleportTo"));
        assertFalse(staging.contains("startRiding"));
        for (String bypass:new String[]{"setHealth(","respawn(","setInvulnerable(","setNoGravity(","setGameMode("})
            assertFalse(s.contains(bypass),"Do not mask fixture deaths with "+bypass);
        assertTrue(s.contains("private static void prepareCorridor() {\n        verifySetupStaging();"));
        assertTrue(s.contains("private static void mountWhenReady() {\n        verifySetupStaging();"));
        assertTrue(s.contains("requireOriginalPlayerAlive(\"before flight or case completion\")"));
        assertTrue(s.contains("private static void verifyRider(ServerSubLevel body) {\n        requireOriginalPlayerAlive"));
        assertTrue(s.contains("player.server.getPlayerList().getPlayer(originalPlayerId)==player"));
        assertTrue(s.contains("player.isAlive() && player.getHealth()>0"));
        assertTrue(s.contains("!player.isNoGravity() && !player.getAbilities().flying"));
        assertTrue(s.contains("source.hasChunkAt(player.blockPosition())"));
        assertTrue(s.contains("&& stagingHeadroomClear()"));
    }
}
