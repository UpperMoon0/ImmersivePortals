package qouteall.imm_ptl.core.compat;

import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import qouteall.imm_ptl.core.compat.mixin.sodium.MixinSodiumRenderSectionManager;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class SodiumInitialMeshUpdateTest {
    @Test
    void updateBetweenInitialSnapshotAndUploadIsRetainedAfterReload() throws Exception {
        var section = new RenderSection(null, 0, 5, -1);
        // submitSectionTask records its frame and clears pending work before the
        // async result arrives. scheduleRebuild must retain subsequent updates.
        section.setLastSubmittedFrame(1);
        section.clearPendingUpdate();
        assertFalse(section.isBuilt());

        var original = scheduler(false, section);
        schedule(original);
        assertEquals(0, section.getPendingUpdate(), "Reproduce the pinned built-only gate dropping the update");
        assertFalse(original.getClass().getField("needsGraphUpdate").getBoolean(original));

        var fixed = scheduler(true, section);
        schedule(fixed);
        assertEquals(2, section.getPendingUpdate(), "Keep Sodium's REBUILD update type");
        assertTrue(fixed.getClass().getField("needsGraphUpdate").getBoolean(fixed));
        schedule(fixed);
        assertEquals(2, section.getPendingUpdate(), "Repeated changes coalesce through the real scheduler");
    }

    @Test
    void notYetSubmittedSectionKeepsItsInitialBuild() throws Exception {
        var section = new RenderSection(null, 0, 5, -1);
        assertEquals(-1, section.getLastSubmittedFrame());
        var fixed = scheduler(true, section);
        schedule(fixed);
        assertEquals(0, section.getPendingUpdate());
        assertFalse(fixed.getClass().getField("needsGraphUpdate").getBoolean(fixed));
    }

    @Test
    void uploadedSectionsKeepTheExistingRebuildPath() throws Exception {
        var section = new RenderSection(null, 0, 5, -1);
        var built = RenderSection.class.getDeclaredField("built");
        built.setAccessible(true);
        built.setBoolean(section, true);
        assertTrue(redirect(section));
        var fixed = scheduler(true, section);
        schedule(fixed);
        assertEquals(2, section.getPendingUpdate());
    }

    private static void schedule(Object scheduler) throws Exception {
        scheduler.getClass().getMethod("scheduleRebuild", int.class, int.class, int.class, boolean.class)
            .invoke(scheduler, 0, 5, -1, false);
    }

    public static boolean redirect(RenderSection section) throws Exception {
        var hook = MixinSodiumRenderSectionManager.class.getDeclaredMethod("ip_hasCapturedMesh", RenderSection.class);
        assertTrue(java.lang.reflect.Modifier.isPrivate(hook.getModifiers()), "Mixin requires private static handlers");
        hook.setAccessible(true);
        return (boolean) hook.invoke(null, section);
    }

    /** Execute the pinned scheduler bytecode without constructing its GL renderer.
     * Copy only its scheduling methods/fields; replace the thread assertion with
     * true and, in the fixed variant only, apply the production redirect. */
    private static Object scheduler(boolean fixed, RenderSection section) throws Exception {
        var node = new ClassNode();
        try (var input = RenderSectionManager.class.getResourceAsStream("RenderSectionManager.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        String originalName = node.name;
        node.name = "qouteall/imm_ptl/core/compat/SodiumScheduler" + (fixed ? "Fixed" : "Original");
        node.interfaces.clear();
        node.innerClasses.clear();
        node.nestHostClass = null;
        node.nestMembers = null;
        node.fields.removeIf(f -> !Set.of("sectionCache", "sectionByPosition", "lastFrameAtTime",
            "cameraPosition", "needsGraphUpdate", "NEARBY_REBUILD_DISTANCE").contains(f.name));
        node.fields.forEach(f -> f.access = (f.access & Opcodes.ACC_STATIC) | Opcodes.ACC_PUBLIC);
        node.methods.removeIf(m -> !Set.of("scheduleRebuild", "upgradePendingUpdate", "markGraphDirty",
            "shouldPrioritizeTask").contains(m.name));
        int gates = 0;
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field && field.owner.equals(originalName)) field.owner = node.name;
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(originalName)) call.owner = node.name;
                    if (call.name.equals("validateCurrentThread")) {
                        method.instructions.set(call, new InsnNode(Opcodes.ICONST_1));
                    } else if (method.name.equals("scheduleRebuild") && call.name.equals("isBuilt")) {
                        assertEquals("net/caffeinemc/mods/sodium/client/render/chunk/RenderSection", call.owner);
                        assertEquals("()Z", call.desc);
                        gates++;
                        if (fixed) method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "qouteall/imm_ptl/core/compat/SodiumInitialMeshUpdateTest",
                            "redirect", "(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;)Z", false));
                    }
                }
            }
        }
        assertEquals(1, gates, "The production redirect must match exactly one pinned scheduler gate");
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        var fixture = new ClassLoader(SodiumInitialMeshUpdateTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, writer.toByteArray(), 0, writer.toByteArray().length); }
        }.define();
        Object result = fixture.getConstructor().newInstance();
        fixture.getField("sectionCache").set(result, new ClonedChunkSectionCache(null));
        var sections = new Long2ReferenceOpenHashMap<RenderSection>();
        sections.put(net.minecraft.core.SectionPos.asLong(0, 5, -1), section);
        fixture.getField("sectionByPosition").set(result, sections);
        return result;
    }
}
