package qouteall.imm_ptl.core.chunk_loading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class MixinEntityCompatibilityTest {

    @Test
    @DisplayName("Mixin Conflict Verification: MixinEntity uses WrapOperation instead of Redirect")
    void verifyMixinEntityUsesWrapOperation() throws Exception {
        String resource = "qouteall/imm_ptl/core/mixin/common/collision/MixinEntity.class";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "MixinEntity.class missing from build output");
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);

            MethodNode redirectMethod = node.methods.stream()
                .filter(m -> m.name.equals("redirectHandleCollisions"))
                .findFirst()
                .orElse(null);

            assertNotNull(redirectMethod, "redirectHandleCollisions method not found in MixinEntity");

            boolean hasWrapOperation = false;
            boolean hasRedirect = false;

            if (redirectMethod.visibleAnnotations != null) {
                for (AnnotationNode ann : redirectMethod.visibleAnnotations) {
                    if (ann.desc.contains("WrapOperation")) {
                        hasWrapOperation = true;
                    }
                    if (ann.desc.contains("Redirect")) {
                        hasRedirect = true;
                    }
                }
            }

            assertTrue(hasWrapOperation, "MixinEntity must use @WrapOperation to prevent Sable mixin conflict");
            assertFalse(hasRedirect, "MixinEntity must NOT use @Redirect which causes collision conflicts");
            System.out.println("\n=== MIXIN VERIFICATION SUCCESSFUL ===");
            System.out.println("MixinEntity.redirectHandleCollisions correctly uses @WrapOperation.");
        }
    }
}
