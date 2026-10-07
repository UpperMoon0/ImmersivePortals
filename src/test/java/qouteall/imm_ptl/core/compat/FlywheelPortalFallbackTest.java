package qouteall.imm_ptl.core.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Structural/runtime-policy tests; rendered correctness is covered by the graphical matrix. */
class FlywheelPortalFallbackTest {
    @Test
    void fallbackIsScopedToAlternateOrClippedViews() {
        assertFalse(IPFlywheelCompat.needsVanillaRenderer(false, false, false));
        for (int flags = 1; flags < 8; flags++) {
            assertTrue(IPFlywheelCompat.needsVanillaRenderer((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0));
        }
        // Nested exit restores the parent scope without changing global backend state.
        assertTrue(IPFlywheelCompat.needsVanillaRenderer(true, true, true));
        assertTrue(IPFlywheelCompat.needsVanillaRenderer(true, true, false));
        assertFalse(IPFlywheelCompat.needsVanillaRenderer(false, false, false));
    }

    @Test
    void discoveryAndRenderingUseComplementaryVanillaPolicies() throws Exception {
        var manager = method("MixinFlywheelVisualizationManager", "ip_useVanillaInAlternateViews");
        assertTrue(invokes(manager, "useVanillaRenderer"));
        assertTrue(invokes(manager, "setReturnValue"));
        var helper = method("MixinFlywheelVisualizationHelper", "ip_retainVanillaRenderEntry");
        assertTrue(invokes(helper, "setReturnValue"), "Compiled BE entries must remain available inside portals");
        var dispatcher = method("MixinFlywheelBlockEntityRenderDispatcher", "ip_skipVisualizedMainViewEntry");
        assertTrue(invokes(dispatcher, "supportsVisualization"));
        assertTrue(invokes(dispatcher, "skipVanillaRender"));
        assertTrue(invokes(dispatcher, "cancel"), "Retained entries must not duplicate visualized main-view geometry");
    }

    @Test
    void sameLevelRenderContextRestoresEvenOnExceptionalExit() throws Exception {
        var method = method("MixinFlywheelLevelRenderer", "ip_restoreFlywheelRenderContext");
        assertTrue(method.tryCatchBlocks.stream().anyMatch(block -> block.type == null),
            "A finally handler must restore context when recursive rendering throws");
        int reads = 0, writes = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("ip_getFlywheelRenderContext")) reads++;
                if (call.name.equals("ip_setFlywheelRenderContext")) writes++;
                assertFalse(call.name.startsWith("reset") || call.name.equals("chooseBackend"),
                    "Entering a portal must not reset a level manager or change the selected backend");
            }
        }
        assertEquals(1, reads);
        assertEquals(2, writes, "Context must restore on both normal and exceptional paths");
        assertTrue(invokes(method, "call"));
    }

    @Test
    void contextFieldIsResolvedAfterMixinFieldsAreMerged() throws Exception {
        for (String accessor : new String[]{"ip_getFlywheelRenderContext", "ip_setFlywheelRenderContext"}) {
            var method = method("MixinFlywheelLevelRenderer", accessor);
            assertNotNull(method.visibleAnnotations);
            assertTrue(method.visibleAnnotations.stream().anyMatch(annotation ->
                annotation.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")
                    && annotation.values.contains("flywheel$renderContext")),
                "The upstream-injected field must use a late-resolved accessor, not a preparation-time shadow");
        }
    }

    @Test
    void legacyGlobalReloadCancellationIsGone() throws Exception {
        var loader = getClass().getClassLoader();
        for (String old : new String[]{"CrumblingRenderer", "ProgramCompiler", "QuadConverter"}) {
            assertNull(loader.getResource("qouteall/imm_ptl/core/compat/mixin/flywheel/MixinFlywheel" + old + ".class"));
        }
    }

    private static boolean invokes(MethodNode method, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return true;
        }
        return false;
    }

    private static MethodNode method(String className, String methodName) throws Exception {
        String path = "qouteall/imm_ptl/core/compat/mixin/flywheel/" + className + ".class";
        try (InputStream stream = FlywheelPortalFallbackTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node.methods.stream().filter(method -> method.name.equals(methodName)).findFirst().orElseThrow();
        }
    }
}
