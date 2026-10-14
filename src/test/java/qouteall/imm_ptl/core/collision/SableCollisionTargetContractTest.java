package qouteall.imm_ptl.core.collision;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Pins the actual optional Sable jar, without booting Minecraft or a native physics engine. */
class SableCollisionTargetContractTest {
    private static final String ROOT = "dev/ryanhcode/sable/";
    private static final String MIXIN = ROOT + "mixin/entity/entity_sublevel_collision/EntityMixin";
    private static final String SOLVER = ROOT + "sublevel/entity_collision/SubLevelEntityCollision";
    private static final String INFO = SOLVER + "$CollisionInfo";

    @Test
    void nativeResultIsPublishedBeforeTerrainAndPassedByIdentity() throws Exception {
        MethodNode redirect = method(MIXIN, "sable$collideRedirect");
        int nativeSolve = callIndex(redirect, SOLVER, "collide");
        int publication = fieldIndex(redirect, "sable$collisionInfo", Opcodes.PUTFIELD);
        int terrain = callIndex(redirect, MIXIN, "collide");
        assertTrue(nativeSolve >= 0 && publication > nativeSolve && terrain > publication,
            "The scope requires publication after native collision and before terrain collision");
        AbstractInsnNode argument = previousCode(redirect.instructions.get(terrain));
        assertEquals(Opcodes.ALOAD, argument.getOpcode());
        int motion = fieldIndex(redirect, "motion", Opcodes.GETFIELD);
        assertTrue(motion > publication && motion < terrain, "Terrain must receive the newly published motion");
        AbstractInsnNode stored = nextCode(redirect.instructions.get(motion));
        assertTrue(stored instanceof VarInsnNode && stored.getOpcode() == Opcodes.ASTORE);
        assertEquals(((VarInsnNode) stored).var, ((VarInsnNode) argument).var,
            "The exact info.motion object must flow unchanged to the terrain call");
        assertEquals(1, callCount(redirect, MIXIN, "collide"), "Exactly one world-terrain solve per native redirect");
    }

    @Test
    void solverAllocatesFreshRecordsAndDoesNotDirectlyCallEntityTerrain() throws Exception {
        MethodNode solver = method(SOLVER, "collide");
        int allocations = 0;
        for (var instruction : solver.instructions) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(INFO)) allocations++;
            if (instruction instanceof MethodInsnNode call) {
                assertNotEquals("sable$vanillaCollide", call.name, "Audit any new pre-publication terrain queries");
                assertFalse(call.owner.equals("net/minecraft/world/entity/Entity") && call.name.equals("collide"));
            }
        }
        assertEquals(2, allocations, "Server players and other entities must each construct a fresh native record");
    }

    @Test
    void emptyNativeRecordsAreLegalForLivingConsumers() throws Exception {
        MethodNode constructor = method(INFO, "<init>");
        for (var instruction : constructor.instructions) {
            assertFalse(instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD,
                "Re-audit guard state if Sable adds non-default record initialization");
        }
        MethodNode living = method(ROOT + "mixin/entity/entity_sublevel_collision/LivingEntityMixin", "sable$beforeAnimation");
        for (String field : new String[] {"inheritedMotion", "firstCollisions"}) {
            int read = fieldIndex(living, field, Opcodes.GETFIELD);
            assertTrue(read >= 0);
            AbstractInsnNode guard = nextCode(living.instructions.get(read));
            assertTrue(guard instanceof JumpInsnNode && guard.getOpcode() == Opcodes.IFNULL,
                "Living consumer must accept null " + field + " from native no-contact paths and deliberate bypasses");
        }
    }

    @Test
    void nativeRedirectReadsTheSharedRecordAfterTerrainAndTrackingSetterHasExtraSideEffects() throws Exception {
        MethodNode redirect = method(MIXIN, "sable$collideRedirect");
        int terrain = callIndex(redirect, MIXIN, "collide");
        boolean laterRead = false;
        for (int i = terrain + 1; i < redirect.instructions.size(); i++) {
            if (redirect.instructions.get(i) instanceof FieldInsnNode field && field.name.equals("sable$collisionInfo")) laterRead = true;
        }
        assertTrue(laterRead, "Nested movement must restore the outer record before native flag updates");
        assertEquals(1, callCount(method(MIXIN, "sable$setTrackingSubLevel"), MIXIN, "sable$setLastTrackingSubLevelID"),
            "Nested restoration uses a raw field accessor, not this side-effecting public setter");
    }

    private static MethodNode method(String owner, String name) throws Exception {
        try (InputStream input = SableCollisionTargetContractTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(input, owner);
            ClassNode node = new ClassNode();
            new ClassReader(input.readAllBytes()).accept(node, 0);
            return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
        }
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return i;
        }
        return -1;
    }

    private static int callCount(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) count++;
        }
        return count;
    }

    private static int fieldIndex(MethodNode method, String name, int opcode) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof FieldInsnNode field && field.name.equals(name) && field.getOpcode() == opcode) return i;
        }
        return -1;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
