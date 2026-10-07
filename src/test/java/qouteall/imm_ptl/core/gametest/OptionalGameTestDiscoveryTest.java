package qouteall.imm_ptl.core.gametest;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the Class.forName + reflection boundary used before NeoForge's mod guards run. */
class OptionalGameTestDiscoveryTest {
    private static final String ROOT = "qouteall/imm_ptl/core/gametest/";
    private static final String QIO = ROOT.replace('/', '.') + "sablee2e.QioAssemblyGameTest";
    private static final String MOD_LIST = "net/neoforged/fml/ModList";

    @Test
    void everyDiscoveredHolderAndSubscriberLoadsWithoutOptionalMods() throws Exception {
        var discovery = discoveryClasses();
        assertTrue(discovery.contains(QIO), "The regression must include the formerly unsafe QIO holder");
        assertTrue(discovery.size() >= 10, "Audit all holders and auto-subscribers, including nested classes");
        var loader = new DiscoveryLoader(Set.of(), false);
        for (String name : discovery) {
            Class<?> holder = Class.forName(name, true, loader);
            holder.getDeclaredConstructors();
            holder.getDeclaredFields();
            for (var method : holder.getDeclaredMethods()) {
                // Also force annotation/signature resolution, as NeoForge discovery does.
                method.getDeclaredAnnotations();
                method.getParameterTypes();
                method.getReturnType();
                if (method.getName().equals("tests") && method.getParameterCount() == 0) {
                    assertEquals(List.of(), method.invoke(null), name);
                }
            }
        }
        assertEquals(List.of(), loader.forbiddenAttempts,
            "No optional dependency or typed implementation may even be requested during absent-mod discovery");
    }

    @Test
    void qioRequiresBothModsBeforeLinkingItsImplementation() throws Exception {
        for (Set<String> mods : List.of(Set.<String>of(), Set.of("sable"), Set.of("mekanism"))) {
            var loader = new DiscoveryLoader(mods, false);
            var holder = Class.forName(QIO, true, loader);
            assertEquals(List.of(), holder.getMethod("tests").invoke(null), mods.toString());
            assertEquals(List.of(), loader.forbiddenAttempts);
        }
        // A test double verifies dispatch without initializing Minecraft/Sable inside JUnit.
        var loader = new DiscoveryLoader(Set.of("sable", "mekanism"), true);
        var holder = Class.forName(QIO, true, loader);
        assertEquals(List.of("implementation reached"), holder.getMethod("tests").invoke(null));
        assertTrue(loader.implementationReached);
    }

    private static List<String> discoveryClasses() throws Exception {
        var location = OptionalGameTestDiscoveryTest.class.getClassLoader().getResource(QIO.replace('.', '/') + ".class");
        assertNotNull(location);
        List<String> result = new ArrayList<>();
        try (var paths = Files.walk(Path.of(location.toURI()).getParent().getParent())) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                var node = new ClassNode();
                new ClassReader(Files.readAllBytes(path)).accept(node, ClassReader.SKIP_CODE);
                var annotations = new ArrayList<AnnotationNode>();
                if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
                if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
                if (annotations.stream().anyMatch(a -> a.desc.endsWith("/GameTestHolder;")
                    || a.desc.endsWith("/EventBusSubscriber;"))) {
                    result.add(node.name.replace('/', '.'));
                }
            }
        }
        return result;
    }

    private static final class DiscoveryLoader extends ClassLoader {
        final List<String> forbiddenAttempts = new ArrayList<>();
        final Set<String> loadedMods;
        final boolean stubQio;
        boolean implementationReached;

        DiscoveryLoader(Set<String> loadedMods, boolean stubQio) {
            super(OptionalGameTestDiscoveryTest.class.getClassLoader());
            this.loadedMods = loadedMods;
            this.stubQio = stubQio;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                byte[] bytes;
                if (name.equals(MOD_LIST.replace('/', '.'))) {
                    bytes = modListStub(loadedMods);
                } else if (name.equals(QIO + "Impl") && stubQio) {
                    implementationReached = true;
                    bytes = qioStub();
                } else if (name.startsWith("dev.ryanhcode.") || name.startsWith("mekanism.")
                    || name.startsWith("com.simibubi.create.") || name.startsWith("dev.engine_room.flywheel.")
                    || name.startsWith("org.valkyrienskies.") || name.startsWith("dev.erikson.simulated.")
                    || name.startsWith(ROOT.replace('/', '.')) && (name.contains("GameTestImpl")
                        || name.endsWith("AssemblyFixtureSequence"))) {
                    forbiddenAttempts.add(name);
                    throw new ClassNotFoundException("Optional dependency blocked by discovery regression: " + name);
                } else if (name.startsWith(ROOT.replace('/', '.'))) {
                    try (InputStream stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (stream == null) throw new ClassNotFoundException(name);
                        bytes = stream.readAllBytes();
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                } else {
                    return super.loadClass(name, resolve);
                }
                Class<?> defined = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(defined);
                return defined;
            }
        }
    }

    private static byte[] modListStub(Set<String> mods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MOD_LIST, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get", "()L" + MOD_LIST + ";", null, null);
        get.visitCode();
        get.visitTypeInsn(Opcodes.NEW, MOD_LIST);
        get.visitInsn(Opcodes.DUP);
        get.visitMethodInsn(Opcodes.INVOKESPECIAL, MOD_LIST, "<init>", "()V", false);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        MethodVisitor isLoaded = cw.visitMethod(Opcodes.ACC_PUBLIC, "isLoaded", "(Ljava/lang/String;)Z", null, null);
        isLoaded.visitCode();
        isLoaded.visitInsn(Opcodes.ICONST_0);
        for (String mod : mods) {
            isLoaded.visitLdcInsn(mod);
            isLoaded.visitVarInsn(Opcodes.ALOAD, 1);
            isLoaded.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            isLoaded.visitInsn(Opcodes.IOR);
        }
        isLoaded.visitInsn(Opcodes.IRETURN);
        isLoaded.visitMaxs(0, 0);
        isLoaded.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] qioStub() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, (QIO + "Impl").replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor tests = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tests", "()Ljava/util/List;", null, null);
        tests.visitCode();
        tests.visitLdcInsn("implementation reached");
        tests.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;", true);
        tests.visitInsn(Opcodes.ARETURN);
        tests.visitMaxs(0, 0);
        tests.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
