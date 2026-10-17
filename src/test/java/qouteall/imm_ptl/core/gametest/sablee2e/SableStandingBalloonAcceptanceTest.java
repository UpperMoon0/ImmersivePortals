package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SableStandingBalloonAcceptanceTest {
    private static final UUID BODY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HANDOFF = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void aNewAttemptCannotExposeAnEarlierCompletion() throws Exception {
        String handoff=java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/qouteall/imm_ptl/core/compat/sable/SableServerFirstClientHandoff.java"));
        int start=handoff.indexOf("private static void startMovementHold(");
        int clear=handoff.indexOf("standingCompletion = null;",start);
        int hold=handoff.indexOf("if (movementHold != null)",start);
        assertTrue(clear>start && clear<hold);
    }

    @Test void readinessRequiresEveryObservedChunkAndNonemptyCoverage() {
        assertTrue(SableStandingBalloonAcceptance.allPresent(Map.of("0,0", true, "0,1", true)));
        assertFalse(SableStandingBalloonAcceptance.allPresent(Map.of("0,0", true, "0,1", false)));
        assertFalse(SableStandingBalloonAcceptance.allPresent(Map.of()));
    }
    @Test void coldStartRequiresNonemptyAllAbsentCoverage() {
        assertTrue(SableStandingBalloonAcceptance.allAbsent(Map.of("1024,1024", false, "1024,1025", false)));
        assertFalse(SableStandingBalloonAcceptance.allAbsent(Map.of("1024,1024", false, "1024,1025", true)));
        assertFalse(SableStandingBalloonAcceptance.allAbsent(Map.of()));
    }
    @Test void successMustCorrelateBodyNonceAndBothWorlds() {
        assertTrue(completed(BODY, HANDOFF, "minecraft:the_nether", "minecraft:overworld", 0));
        assertFalse(completed(HANDOFF, HANDOFF, "minecraft:the_nether", "minecraft:overworld", 0));
        assertFalse(completed(BODY, null, "minecraft:the_nether", "minecraft:overworld", 0));
        assertFalse(completed(BODY, HANDOFF, "minecraft:overworld", "minecraft:overworld", 0));
        assertFalse(completed(BODY, HANDOFF, "minecraft:the_nether", "minecraft:the_nether", 0));
    }
    @Test void laterSuccessCannotEraseRejectedOrExpiredAttempts() {
        for (long failures : new long[]{-1, 1, 2})
            assertFalse(completed(BODY, HANDOFF, "minecraft:the_nether", "minecraft:overworld", failures));
    }
    private static boolean completed(UUID body, UUID handoff, String source, String destination, long failures) {
        return SableStandingBalloonAcceptance.completed(BODY, body, handoff, source, destination, failures);
    }
    @Test void chunkInventoryNeverCreatesChunksOrConflatesWorldAndPlotStorage() throws Exception {
        String chunks = fixture("SableStandingBalloonChunks");
        assertTrue(chunks.contains("body.getPlot().getLoadedChunks()"));
        assertTrue(chunks.contains("holder.getChunk().getPos().toLong()"));
        assertFalse(chunks.contains("container.getChunk("));
        assertTrue(chunks.contains("level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false)"));
        assertTrue(chunks.contains("body.logicalPose().transformPosition("));
        assertTrue(chunks.contains("playerPosition.x"));
        assertTrue(chunks.contains("\"world_chunks\", worldChunks, \"plot_chunks\", plotChunks"));
    }
    @Test void serverReadinessAndForceLoadChecksPrecedeObservationAndRetainFlightOracles() throws Exception {
        String server=fixture("SableStandingBalloonServer");
        String fly=between(server,"private static void fly()", "private static void observe()");
        assertTrue(fly.contains("requireNoForceLoad(body(source));"));
        assertTrue(fly.contains("requireNoForceLoad(body(destination));"));
        assertTrue(fly.contains("SableStandingBalloonChunks.snapshot(destination,arrived,player.position())"));
        String observe=between(server,"private static void observe()", "private static void requireNoForceLoad(");
        assertTrue(observe.contains("requireNoForceLoad(body);"));
        assertTrue(observe.indexOf("require(SableStandingBalloonChunks.ready(destinationReadiness),")
            < observe.indexOf("destination.getBlockState("));
        assertTrue(observe.contains("if(settledSamples<80) return;"));
        assertTrue(observe.contains("body.logicalPose().position().y-arrivalY>0.5"));
        assertTrue(server.contains("if (++ticks>4000)"));
        assertTrue(server.contains("sourceReadiness=SableStandingBalloonChunks.snapshot(source,body,player.position())"));
    }
    @Test void clientRequiresCleanCompletionAndRetainsOnlySuccessfulReadiness() throws Exception {
        String client=fixture("SableStandingBalloonClient");
        assertTrue(client.contains("getUnsuccessfulStandingHandoffs()-unsuccessfulAtStart"));
        assertTrue(client.contains("completion==null || !SableStandingBalloonAcceptance.completed(id,completion.supportId(),completion.handoffId(),"));
        assertTrue(client.indexOf("if(!SableStandingBalloonChunks.ready(candidateReadiness))")
            < client.indexOf("sourceReadiness=candidateReadiness"));
        assertTrue(client.contains("sourceContainer.getSubLevel(id)==null"));
        assertTrue(client.indexOf("if(error>=1)throw") < client.indexOf("if(!sourceRetired ||"));
        assertTrue(client.contains("if(stable>0)throw"));
        assertTrue(client.contains("if(++readinessWaitTicks>200)throw"));
        assertTrue(client.indexOf("SableStandingBalloonAcceptance.completed(") < client.indexOf("if(++stable>=40)"));
        assertTrue(client.contains("if(++ticks>4000)"));
    }
    @Test void terminalDiagnosticsRequireNativeRebaseAndRetainFailureHistory() throws Exception {
        String handoff=Files.readString(Path.of("src/main/java/qouteall/imm_ptl/core/compat/sable/SableServerFirstClientHandoff.java"));
        String rebase=between(handoff,"public static void prepareStandingMovement(","private static boolean isAttachedToCurrentSableSubLevel(");
        assertTrue(rebase.indexOf("standingCompletion = new StandingCompletionSnapshot(") > rebase.indexOf("McHelper.setPosAndLastTickPos("));
        assertEquals(handoff.indexOf("standingCompletion = new StandingCompletionSnapshot("), handoff.lastIndexOf("standingCompletion = new StandingCompletionSnapshot("));
        String apply=between(handoff,"private static boolean tryApplyReady(","public static void prepareStandingMovement(");
        assertFalse(apply.contains("recordUnsuccessfulStandingHandoff("), "Normal standing transform reuse is not a failure");
        String tick=between(handoff,"private static void tick()","private static boolean tryApplyReady(");
        assertTrue(tick.contains("recordUnsuccessfulStandingHandoff(ready.rider());"));
        assertTrue(tick.contains("recordUnsuccessfulStandingHandoff(pending != null ? pending.rider()"));
        assertTrue(handoff.contains("if (!success) {\n            recordUnsuccessfulStandingHandoff(expected.rider());"));
        assertFalse(handoff.contains("unsuccessfulStandingHandoffs ="));
    }
    @Test void coldProbesAreNonCreatingBoundedAndPrecedeAssembly() throws Exception {
        String probe=fixture("SableStandingBalloonColdChunks");
        assertTrue(probe.contains("existingLevel.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false)"));
        String server=fixture("SableStandingBalloonServer");
        String cold=between(server,"private static void awaitColdDestination()", "private static void assemble()");
        assertTrue(cold.contains("phaseTicks<=600"));
        assertTrue(cold.contains("if(absentSamples<20) return;"));
        assertTrue(cold.indexOf("balloon-cold-server-")<cold.indexOf("assemble();"));
        assertTrue(server.contains("ChunkVisibility.foreachBaseChunkLoaders(observer"));
        String client=fixture("SableStandingBalloonClient");
        String clientCold=client.substring(client.indexOf("private static boolean observeColdStart("));
        assertTrue(clientCold.contains("ClientWorldLoader.getClientWorlds()"));
        assertFalse(clientCold.contains("ClientWorldLoader.getWorld("));
        assertTrue(clientCold.contains("if(++coldTicks>600)"));
        assertTrue(clientCold.contains("if(coldSamples<20)"));
    }
    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/qouteall/imm_ptl/core/gametest/sablee2e/"+name+".java"));
    }
    private static String between(String text,String start,String end) {
        return text.substring(text.indexOf(start),text.indexOf(end));
    }
}
