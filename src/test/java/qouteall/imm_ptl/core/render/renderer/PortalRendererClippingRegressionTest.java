package qouteall.imm_ptl.core.render.renderer;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import qouteall.imm_ptl.core.collision.CollisionHelper;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for recursive portal render leakage around mirrors and
 * Portal Helper portal clusters.
 *
 * The rendering failure had two parts worth pinning independently:
 * 1. the geometric predicate must only reject apertures fully behind the
 *    active clipping plane; intersecting/touching apertures must survive;
 * 2. PortalRenderer must actually apply that predicate while recursively
 *    discovering portals, before allowing a nested portal through the normal
 *    recursion filters.
 */
class PortalRendererClippingRegressionTest {

    private static final String PORTAL_RENDERER =
        "qouteall/imm_ptl/core/render/renderer/PortalRenderer";
    private static final String PORTAL_RENDERING =
        "qouteall/imm_ptl/core/render/context_management/PortalRendering";
    private static final String COLLISION_HELPER =
        "qouteall/imm_ptl/core/collision/CollisionHelper";
    private static final String PORTAL =
        "qouteall/imm_ptl/core/portal/Portal";

    @Test
    void clippingPredicateRejectsOnlyFullyHiddenApertures() {
        Vec3 planePos = Vec3.ZERO;
        Vec3 planeNormal = new Vec3(1, 0, 0);

        AABB fullyBehind = new AABB(-2, -1, -1, -1, 1, 1);
        AABB intersecting = new AABB(-1, -1, -1, 1, 1, 1);
        AABB fullyInFront = new AABB(1, -1, -1, 2, 1, 1);
        AABB touchingFromBehind = new AABB(-1, -1, -1, 0, 1, 1);

        assertTrue(CollisionHelper.isBoxFullyBehindPlane(
            planePos, planeNormal, fullyBehind
        ));
        assertFalse(CollisionHelper.isBoxFullyBehindPlane(
            planePos, planeNormal, intersecting
        ));
        assertFalse(CollisionHelper.isBoxFullyBehindPlane(
            planePos, planeNormal, fullyInFront
        ));
        assertFalse(CollisionHelper.isBoxFullyBehindPlane(
            planePos, planeNormal, touchingFromBehind
        ), "an aperture touching the clipping plane must not disappear at its edge");

        // Pin sign handling too: reversing the plane should reverse which side is hidden.
        assertTrue(CollisionHelper.isBoxFullyBehindPlane(
            planePos, planeNormal.scale(-1), fullyInFront
        ));
    }

    @Test
    void recursivePortalDiscoveryKeepsClippingGuardBeforePortalAcceptance() {
        ClassNode renderer = load(PORTAL_RENDERER);
        MethodNode method = renderer.methods.stream()
            .filter(m -> m.name.equals("shouldSkipRenderingPortal"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "PortalRenderer lacks shouldSkipRenderingPortal"
            ));

        int activePlaneCall = -1;
        int behindPlaneCall = -1;
        int recursionAcceptanceCall = -1;
        int behindPlaneCallCount = 0;
        MethodInsnNode behindPlaneInsn = null;
        int index = 0;

        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                if (PORTAL_RENDERING.equals(call.owner)
                    && "getActiveClippingPlane".equals(call.name)) {
                    activePlaneCall = index;
                }
                if (COLLISION_HELPER.equals(call.owner)
                    && "isBoxFullyBehindPlane".equals(call.name)) {
                    behindPlaneCall = index;
                    behindPlaneCallCount++;
                    behindPlaneInsn = call;
                }
                if (PORTAL.equals(call.owner)
                    && "cannotRenderInMe".equals(call.name)) {
                    recursionAcceptanceCall = index;
                }
            }
            index++;
        }

        assertTrue(activePlaneCall >= 0,
            "recursive portal discovery must read the active clipping plane");
        assertEquals(1, behindPlaneCallCount,
            "recursive portal discovery must apply exactly one behind-plane aperture guard");
        assertTrue(behindPlaneCall > activePlaneCall,
            "the aperture must be tested against the active clipping plane");
        assertTrue(recursionAcceptanceCall > behindPlaneCall,
            "hidden apertures must be rejected before normal recursive portal acceptance");
        assertPredicateResultControlsEarlySkip(behindPlaneInsn);
    }

    private static void assertPredicateResultControlsEarlySkip(MethodInsnNode behindPlaneInsn) {
        assertTrue(behindPlaneInsn != null, "missing behind-plane predicate invocation");

        AbstractInsnNode next = nextExecutableInstruction(behindPlaneInsn);
        assertTrue(next instanceof JumpInsnNode,
            "behind-plane predicate result must immediately control a conditional branch");

        JumpInsnNode branch = (JumpInsnNode) next;
        assertEquals(Opcodes.IFEQ, branch.getOpcode(),
            "false must continue normal portal acceptance while true takes the early-skip path");

        AbstractInsnNode truePath = nextExecutableInstruction(branch);
        assertEquals(Opcodes.ICONST_1, truePath.getOpcode(),
            "a fully hidden aperture must make shouldSkipRenderingPortal return true");
        AbstractInsnNode returnInsn = nextExecutableInstruction(truePath);
        assertEquals(Opcodes.IRETURN, returnInsn.getOpcode(),
            "the clipping guard must return immediately instead of discarding its result");
    }

    private static AbstractInsnNode nextExecutableInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) {
            current = current.getNext();
        }
        return current;
    }

    private static ClassNode load(String internalName) {
        try (InputStream in = PortalRendererClippingRegressionTest.class.getResourceAsStream(
            "/" + internalName + ".class"
        )) {
            assertTrue(in != null, "missing compiled class " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
