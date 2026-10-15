package qouteall.imm_ptl.core.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.zip.ZipFile;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseJarIsolationTest {
    private static final String EXCLUDED = "qouteall/imm_ptl/core/gametest/";

    private static void assertNoTestReferences(byte[] bytes, String name) throws IOException {
        ClassReader reader = new ClassReader(bytes);
        // Check every UTF8 constant, including owners, descriptors, handles, and signatures.
        // A disabled diagnostic branch still gets resolved by Mixin's preprocessor.
        for (int index = 1; index < reader.getItemCount(); index++) {
            int offset = reader.getItem(index);
            if (offset != 0 && reader.readByte(offset - 1) == 1) {
                String value = new DataInputStream(new ByteArrayInputStream(bytes, offset,
                    bytes.length - offset)).readUTF();
                assertFalse(value.contains(EXCLUDED), name + " references excluded test code: " + value);
            }
        }
    }

    @Test void packagedClassesDoNotReferenceExcludedGameTests() throws Exception {
        try (ZipFile jar = new ZipFile(System.getProperty("ip.releaseJar"))) {
            assertNotNull(jar.getEntry("qouteall/imm_ptl/core/compat/sable/SableInterpolationHistoryDiagnostics.class"));
            assertNotNull(jar.getEntry("qouteall/imm_ptl/core/compat/sable/SableInterpolationHistoryDiagnostics$Observer.class"));
            assertNotNull(jar.getEntry("qouteall/imm_ptl/core/compat/mixin/sable/MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat.class"));
            int classes = 0;
            for (var entry : jar.stream().toList()) {
                assertFalse(entry.getName().startsWith(EXCLUDED), "Test driver leaked into release jar");
                if (entry.getName().endsWith(".class")) {
                    try (var input = jar.getInputStream(entry)) {
                        assertNoTestReferences(input.readAllBytes(), entry.getName());
                    }
                    classes++;
                }
            }
            assertTrue(classes > 0);
        }
    }

    @Test void scannerRejectsOriginalMissingProbeInvocation() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "BrokenMixin", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "graft", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, EXCLUDED + "sablee2e/SableInterpolationHistoryProbe",
            "verify", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        assertThrows(AssertionError.class, () -> assertNoTestReferences(writer.toByteArray(), "BrokenMixin"));
    }
}
