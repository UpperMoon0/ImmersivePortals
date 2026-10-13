package qouteall.imm_ptl.core.compat.mixin.sable;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Regression contracts for stale client predictions after an authoritative body migration. */
class SableRiderRejectionContractTest {
    @Test void everyRiderCorrectionTargetsTheActualServerDimensionAndPosition() throws Exception {
        MethodNode hook = method(readClass(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinServerTeleportationManager_SableRiderCompat.class"
        ), "ip_prepareRiddenSableBeforePlayer");

        int corrections = 0;
        for (var instruction : hook.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("forceTeleportPlayer")) continue;
            corrections++;
            // Check the real argument producers, not just whether serverLevel() occurs
            // somewhere in the hook. dimensionBefore is local 2 and must never be
            // used here: IP's mounted-player validation bypasses that packet field.
            AbstractInsnNode cursor = previousExecutable(call);
            assertEquals(Opcodes.ICONST_1, cursor.getOpcode(), "Correction must reach the client");
            cursor = previousExecutable(cursor);
            assertCall(cursor, "position");
            cursor = previousExecutable(cursor);
            assertPlayerLoad(cursor);
            cursor = previousExecutable(cursor);
            assertCall(cursor, "dimension");
            cursor = previousExecutable(cursor);
            assertCall(cursor, "serverLevel");
            assertPlayerLoad(previousExecutable(cursor));
        }
        assertEquals(2, corrections, "Both already-migrated and rejected paths need authoritative correction");
    }

    @Test void committedNetworkCleanupCannotBlockARealPhysicsReverseCrossing() throws Exception {
        MethodNode substep = method(readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        ), "afterPhysicsSubstep");
        FieldInsnNode committed = null;
        for (var instruction : substep.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && field.name.equals("committed") && field.owner.endsWith("$ClientHandoff")) {
                assertNull(committed, "Expected one precise staging guard");
                committed = field;
            }
        }
        assertNotNull(committed, "Physics must distinguish uncommitted staging from committed packet cleanup");
        AbstractInsnNode next = nextExecutable(committed);
        assertInstanceOf(JumpInsnNode.class, next);
        JumpInsnNode committedBranch = (JumpInsnNode) next;
        assertEquals(Opcodes.IFNE, committedBranch.getOpcode(),
            "A committed handoff must bypass the wait branch, even while source cleanup is pending");

        int guard = substep.instructions.indexOf(committed);
        int resume = substep.instructions.indexOf(committedBranch.label);
        int crossing = invocationIndex(substep, "findCrossedPortal");
        int migration = invocationIndex(substep, "migrateSubLevel");
        assertTrue(guard < resume && resume < crossing && crossing < migration,
            "Committed bodies must still reach native swept crossing and full body migration");

        boolean remembersStagedSample = false;
        boolean stagingSkipsCrossing = false;
        for (int i = guard + 1; i < resume; i++) {
            var instruction = substep.instructions.get(i);
            if (instruction instanceof MethodInsnNode call && call.name.equals("rememberSample")) {
                remembersStagedSample = true;
            }
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO
                && substep.instructions.indexOf(jump.label) < guard) {
                stagingSkipsCrossing = true;
            }
        }
        assertTrue(remembersStagedSample && stagingSkipsCrossing,
            "Only uncommitted staging should remember the sample and skip that body's crossing");
    }

    @Test void physicalSeamClearanceIsEvaluatedBeforeTheNetworkHandoffGuard() throws Exception {
        MethodNode substep = method(readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        ), "afterPhysicsSubstep");
        int clearance = invocationIndex(substep, "signedDestinationClearance");
        int handoffLookup = -1;
        int clearanceReads = 0;
        for (int i = 0; i < substep.instructions.size(); i++) {
            var instruction = substep.instructions.get(i);
            if (instruction instanceof FieldInsnNode field && field.name.equals("CLIENT_HANDOFFS")) handoffLookup = i;
            if (handoffLookup < 0 && instruction instanceof MethodInsnNode call
                && call.name.equals("signedDestinationClearance")) clearanceReads++;
        }
        assertEquals(2, clearanceReads, "Both previous and current native samples determine genuine seam reversal");
        assertTrue(clearance >= 0 && handoffLookup > clearance,
            "Network cleanup must not suppress physical seam-entry/reversal state updates");
    }

    private static void assertPlayerLoad(AbstractInsnNode instruction) {
        assertInstanceOf(VarInsnNode.class, instruction);
        assertEquals(Opcodes.ALOAD, instruction.getOpcode());
        assertEquals(1, ((VarInsnNode) instruction).var, "Must read the authoritative ServerPlayer argument");
    }

    private static void assertCall(AbstractInsnNode instruction, String name) {
        assertInstanceOf(MethodInsnNode.class, instruction);
        assertEquals(name, ((MethodInsnNode) instruction).name);
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        assertNotNull(instruction, "Missing argument producer");
        return instruction;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        assertNotNull(instruction, "Missing guard branch");
        return instruction;
    }

    private static int invocationIndex(MethodNode method, String name) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof MethodInsnNode call && call.name.equals(name)) return i;
        }
        return -1;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode readClass(String resource) throws Exception {
        try (InputStream stream = SableRiderRejectionContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "Missing compiled production class: " + resource);
            var result = new ClassNode();
            new ClassReader(stream).accept(result, 0);
            return result;
        }
    }
}
