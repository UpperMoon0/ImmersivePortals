package qouteall.imm_ptl.core.collision;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural verification of the collision wrapper wiring on the transformed
 * classes actually shipped in the jar. The base Entity wrapper and the Sable
 * compat wrapper both WrapOperation the same {@code Entity.move -> collide}
 * invocation; this test pins the contract that with Sable present only the
 * compat wrapper invokes the shared hook, so every movement is processed
 * exactly once.
 */
class CollisionWrapperStructureTest {

    private static final String HOOK_OWNER =
        "qouteall/imm_ptl/core/collision/ImmPtlCollisionHooks";
    private static final String HOOK_METHOD = "handlePortalCollision";
    private static final String SABLE_FLAG_OWNER =
        "qouteall/imm_ptl/core/compat/SableCompat";

    private static ClassNode load(String internalName) {
        try (InputStream in = CollisionWrapperStructureTest.class.getResourceAsStream(
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

    private static MethodNode method(ClassNode clazz, String name) {
        MethodNode found = clazz.methods.stream()
            .filter(m -> m.name.equals(name)).findFirst().orElse(null);
        assertTrue(found != null, () -> clazz.name + " lacks method " + name);
        return found;
    }

    private static long countHookInvocations(MethodNode method) {
        long count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                && HOOK_OWNER.equals(call.owner)
                && HOOK_METHOD.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static boolean referencesSableFlag(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field
                && SABLE_FLAG_OWNER.equals(field.owner)
                && "ACTIVE".equals(field.name)) {
                return true;
            }
        }
        return false;
    }

    private static int wrapOperationRequire(MethodNode method) {
        List<AnnotationNode> annotations = method.visibleAnnotations;
        assertTrue(annotations != null, "wrapper has no runtime-visible annotations");
        AnnotationNode wrapOperation = annotations.stream()
            .filter(annotation -> annotation.desc.endsWith("/WrapOperation;"))
            .findFirst().orElse(null);
        assertTrue(wrapOperation != null, "wrapper lacks @WrapOperation");
        for (int i = 0; i < wrapOperation.values.size(); i += 2) {
            if ("require".equals(wrapOperation.values.get(i))) {
                return (Integer) wrapOperation.values.get(i + 1);
            }
        }
        return -1;
    }

    @Test
    void baseWrapperIsConditionalAndSingleWrapped() {
        MethodNode wrapper = method(load("qouteall/imm_ptl/core/mixin/common/collision/MixinEntity"),
            "redirectHandleCollisions");
        assertEquals(1, countHookInvocations(wrapper),
            "base wrapper must invoke the hook exactly once");
        assertTrue(referencesSableFlag(wrapper),
            "base wrapper must consult SableCompat.ACTIVE and pass through when Sable is loaded");
    }

    @Test
    void sableCompatWrapperOwnsTheHook() {
        MethodNode wrapper = method(
            load("qouteall/imm_ptl/core/compat/mixin/sable/MixinEntity_SableCollisionCompat"),
            "immersivePortals$wrapCollisionForSable");
        assertEquals(1, countHookInvocations(wrapper),
            "compat wrapper must invoke the hook exactly once");
        assertEquals(1, wrapOperationRequire(wrapper),
            "compat wrapper must fail fast if Sable's collision target changes");
        assertFalse(referencesSableFlag(wrapper),
            "compat wrapper application is already gated by IPCompatMixinPlugin");
    }

    @Test
    void guardClassIsGoneFromTheHookPath() {
        ClassNode hooks = load(HOOK_OWNER);
        for (MethodNode method : hooks.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call
                    && call.owner.contains("CollisionChainGuard")) {
                    throw new AssertionError(
                        "runtime guard leaked back into ImmPtlCollisionHooks." + method.name);
                }
            }
        }
    }
}
