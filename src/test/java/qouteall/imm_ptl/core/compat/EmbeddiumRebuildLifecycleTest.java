package qouteall.imm_ptl.core.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.embeddedt.embeddium.impl.render.chunk.ChunkUpdateType;
import org.embeddedt.embeddium.impl.render.chunk.lists.SortedRenderLists;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import qouteall.imm_ptl.core.compat.embeddium_compatibility.EmbeddiumRenderingContext;
import qouteall.imm_ptl.core.compat.mixin.embeddium.MixinEmbeddiumRenderSectionManager;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddiumRebuildLifecycleTest {
    @Test
    void remoteOnlyDiscoveriesSurviveFreshPortalContextsUntilNextPassSubmitsThem() throws Exception {
        // Execute the production swap method with target-owned queue storage. The GPU
        // mesher is represented by draining queued tokens, after checking the upstream
        // bytecode still runs updateChunks before discovering the next render list.
        var manager = new ManagerFixture();
        var submitted = new ArrayList<Submission>();
        var expected = new ArrayList<Submission>();
        int frame = 100;
        for (String discovery : List.of("first remote section", "second remote section", "third remote section")) {
            var view = new EmbeddiumRenderingContext(8); // MyGameRenderer allocates this each portal render.
            manager.ip_swapContext(view);
            manager.updateChunks(submitted);
            frame++;
            var camera = new Vec3(frame, 64, frame + 0.5);
            var cameraBlock = BlockPos.containing(camera);
            manager.discoverForNextPass(discovery, frame, camera, cameraBlock);
            expected.add(new Submission(discovery, frame, camera, cameraBlock));
            manager.ip_swapContext(view); // Discard the temporary context afterwards.
        }
        assertEquals(expected.subList(0, 2), submitted);
        assertEquals("third remote section", manager.queues().get(ChunkUpdateType.INITIAL_BUILD).peek());
    }

    private record Submission(Object token, int frame, Vec3 camera, BlockPos cameraBlock) {}

    @Test
    void upstreamConsumesRebuildQueuesBeforeDiscoveringTheNextVisibilitySet() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
            "org/embeddedt/embeddium/impl/render/EmbeddiumWorldRenderer.class")) {
            assertNotNull(input);
            var renderer = new ClassNode();
            new ClassReader(input).accept(renderer, 0);
            var setup = renderer.methods.stream().filter(method -> method.name.equals("setupTerrain")).findFirst().orElseThrow();
            var calls = new ArrayList<String>();
            for (var instruction : setup.instructions) {
                if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("org/embeddedt/embeddium/impl/render/chunk/RenderSectionManager")) {
                    calls.add(call.name);
                }
            }
            assertTrue(calls.contains("updateChunks") && calls.contains("update"));
            assertTrue(calls.indexOf("updateChunks") < calls.indexOf("update"), calls.toString());
        }
    }

    private static final class ManagerFixture extends MixinEmbeddiumRenderSectionManager {
        // This field belongs to the upstream target. If the mixin shadows it again,
        // bind the fixture to that exact shadow so the regression cannot be hidden.
        private Map<ChunkUpdateType, ArrayDeque<Object>> rebuildLists;
        private int lastUpdatedFrame;
        private Vec3 cameraPosition;
        private BlockPos lastCameraPosition;
        private final Field queueField;

        ManagerFixture() throws Exception {
            queueField = targetField("rebuildLists");
            queueField.set(this, emptyQueues());
            setViewField("renderDistance", 12);
            setViewField("renderLists", SortedRenderLists.empty());
        }

        private static Field targetField(String name) throws Exception {
            Field field;
            try {
                field = MixinEmbeddiumRenderSectionManager.class.getDeclaredField(name);
            } catch (NoSuchFieldException absentShadow) {
                field = ManagerFixture.class.getDeclaredField(name);
            }
            field.setAccessible(true);
            return field;
        }

        private void setViewField(String name, Object value) throws Exception {
            var field = MixinEmbeddiumRenderSectionManager.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(this, value);
        }

        @SuppressWarnings("unchecked")
        Map<ChunkUpdateType, ArrayDeque<Object>> queues() throws Exception {
            return (Map<ChunkUpdateType, ArrayDeque<Object>>) queueField.get(this);
        }

        void updateChunks(List<Submission> submitted) throws Exception {
            for (var queue : queues().values()) {
                Object token;
                while ((token = queue.poll()) != null) {
                    submitted.add(new Submission(token, targetField("lastUpdatedFrame").getInt(this),
                        (Vec3) targetField("cameraPosition").get(this),
                        (BlockPos) targetField("lastCameraPosition").get(this)));
                }
            }
        }

        void discoverForNextPass(Object token, int frame, Vec3 camera, BlockPos cameraBlock) throws Exception {
            var queues = emptyQueues();
            queues.get(ChunkUpdateType.INITIAL_BUILD).add(token);
            queueField.set(this, queues);
            targetField("lastUpdatedFrame").setInt(this, frame);
            targetField("cameraPosition").set(this, camera);
            targetField("lastCameraPosition").set(this, cameraBlock);
        }

        private static Map<ChunkUpdateType, ArrayDeque<Object>> emptyQueues() {
            var queues = new EnumMap<ChunkUpdateType, ArrayDeque<Object>>(ChunkUpdateType.class);
            for (var type : ChunkUpdateType.values()) queues.put(type, new ArrayDeque<>());
            return queues;
        }
    }
}
