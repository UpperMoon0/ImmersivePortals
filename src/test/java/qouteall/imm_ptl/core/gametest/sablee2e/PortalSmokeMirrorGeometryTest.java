package qouteall.imm_ptl.core.gametest.sablee2e;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class PortalSmokeMirrorGeometryTest {
    @Test
    void excludedWallCannotOccludeEitherActualMirrorApertureOffset() throws Exception {
        int wallZ = PortalSmokeMirrorGeometry.excludedWallZ();
        double frontFaceZ = wallZ + 1;
        assertTrue(-4 < wallZ, "The reflected camera must see the red wall if clipping fails");
        assertTrue(frontFaceZ < 0, "The complete red block volume must be strictly behind the mirror clipping plane");

        MethodNode mesh = method("qouteall/imm_ptl/core/portal/Portal.class", "renderViewAreaMesh");
        int testedOffsets = 0;
        for (var instruction : mesh.instructions) {
            if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Double offset) {
                assertTrue(frontFaceZ < offset,
                    "The excluded wall must not cover the mirror aperture at z=" + offset);
                assertTrue(offset < 4, "Aperture must remain in front of the real camera");
                testedOffsets++;
            }
        }
        assertEquals(2, testedOffsets, "Exercise both vanilla-back and shader-front mirror mesh offsets");
    }

    @Test
    void serverFixtureUsesTheAuditedExcludedWallGeometry() throws Exception {
        MethodNode setup = method("qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeServer.class", "setup");
        boolean usesGeometry = false;
        for (var instruction : setup.instructions) {
            if (instruction instanceof MethodInsnNode call
                && call.owner.equals("qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeMirrorGeometry")
                && call.name.equals("excludedWallZ")) usesGeometry = true;
        }
        assertTrue(usesGeometry, "The rendering test must actually use the geometry covered by this regression");
        // The old z=-1 wall ended at z=0, in front of the -0.01 vanilla aperture.
        assertFalse(-1 + 1 < -0.01, "Confirm the regression rejects the former fixture");
    }

    @Test
    void observerExclusionIsScopedToTheDisposableMirrorAndVerifiedLive() throws Exception {
        MethodNode setup = method("qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeServer.class", "setup");
        int scopedSettings = 0;
        for (var instruction : setup.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("setDoRenderPlayer")) {
                assertEquals(Opcodes.ICONST_0, call.getPrevious().getOpcode());
                scopedSettings++;
            }
            if (instruction instanceof FieldInsnNode field && field.name.equals("renderYourselfInPortal")) {
                assertNotEquals(Opcodes.PUTSTATIC, field.getOpcode(), "Never change the user's global self-rendering option");
            }
        }
        assertEquals(1, scopedSettings);
        MethodNode capture = method("qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeClient.class", "capture");
        assertTrue(invokes(capture, "verifyMirrorObserverIsolation"), "Pixel acceptance must verify live mirror state");
        MethodNode live = method("qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeClient.class", "verifyMirrorObserverIsolation");
        assertTrue(invokes(live, "getDoRenderPlayer"));
        assertTrue(invokes(live, "isSpectator"));
        assertTrue(invokes(live, "requireIsolatedObserver"));
    }

    @Test
    void invalidObserverIsolationCannotPass() {
        assertDoesNotThrow(() -> PortalSmokeMirrorGeometry.requireIsolatedObserver(true, 1, 1, false));
        assertThrows(IllegalStateException.class, () -> PortalSmokeMirrorGeometry.requireIsolatedObserver(true, 1, 1, true));
        assertThrows(IllegalStateException.class, () -> PortalSmokeMirrorGeometry.requireIsolatedObserver(false, 1, 1, false));
        assertThrows(IllegalStateException.class, () -> PortalSmokeMirrorGeometry.requireIsolatedObserver(true, 2, 1, false));
        assertThrows(IllegalStateException.class, () -> PortalSmokeMirrorGeometry.requireIsolatedObserver(true, 1, 0, false));
    }

    private static boolean invokes(MethodNode method, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) return true;
        }
        return false;
    }

    private static MethodNode method(String resource, String name) throws Exception {
        try (InputStream stream = PortalSmokeMirrorGeometryTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
        }
    }
}
