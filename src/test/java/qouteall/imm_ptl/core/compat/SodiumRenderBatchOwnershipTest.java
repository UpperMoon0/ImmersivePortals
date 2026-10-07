package qouteall.imm_ptl.core.compat;

import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import qouteall.imm_ptl.core.compat.mixin.sodium.MixinSodiumRenderRegion;

import static org.junit.jupiter.api.Assertions.*;

class SodiumRenderBatchOwnershipTest {
    @Test
    void unchangedInnerListCannotReuseOuterCommandsAndOuterResumeInvalidatesAgain() {
        // Real CPU-side lists and native batches, without a world or OpenGL context.
        // Model DefaultChunkRenderer's exact isFilled cache decision after the two
        // stable per-depth lists have stopped triggering their own invalidation.
        var outer = new ChunkRenderList(null);
        var inner = new ChunkRenderList(null);
        var batch = new MultiDrawBatch(4);
        var pass = new TerrainRenderPass(null, false, false);
        var ownership = new MixinSodiumRenderRegion();
        try {
            prepare(outer, 1, 1);
            prepare(inner, 2, 1, 2);
            ownership.ip_prepareBatchForRenderList(pass, batch, outer);
            fill(batch, 1); // The outer view issued one section's commands.

            prepare(inner, 3, 1, 2); // Same inner camera and section set as its previous frame.
            if (!batch.isFilled) fill(batch, inner.getSectionsWithGeometryCount());
            assertTrue(batch.isFilled);
            assertEquals(1, batch.size);
            assertEquals(2, inner.getSectionsWithGeometryCount());

            ownership.ip_prepareBatchForRenderList(pass, batch, inner);
            assertFalse(batch.isFilled);
            assertEquals(0, batch.size);
            fill(batch, 2);
            ownership.ip_prepareBatchForRenderList(pass, batch, inner);
            assertTrue(batch.isFilled, "Repeated same-view draws retain the valid cache");
            assertEquals(2, batch.size);

            // Portal return resumes the saved outer list without prepareForRender.
            ownership.ip_prepareBatchForRenderList(pass, batch, outer);
            assertFalse(batch.isFilled);
            assertEquals(0, batch.size);
        } finally {
            batch.delete();
        }
    }

    @Test
    void irisShadowBatchReplacementDoesNotBorrowTheRegularBatchOwner() {
        var list = new ChunkRenderList(null);
        var pass = new TerrainRenderPass(null, false, false);
        var regular = new MultiDrawBatch(4);
        var shadow = new MultiDrawBatch(4);
        var ownership = new MixinSodiumRenderRegion();
        try {
            ownership.ip_prepareBatchForRenderList(pass, regular, list);
            fill(regular, 1);
            fill(shadow, 2);
            ownership.ip_prepareBatchForRenderList(pass, shadow, list);
            assertFalse(shadow.isFilled);
            ownership.ip_prepareBatchForRenderList(pass, regular, list);
            assertFalse(regular.isFilled);
        } finally {
            shadow.delete();
            regular.delete();
        }
    }

    @Test
    void drawHookMatchesSodiumAndEmbeddiumStillFillsItsScratchBatchUnconditionally() throws Exception {
        var sodium = read("net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer");
        var render = sodium.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(render.instructions.spliterator(), false)
            .anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals("getCachedBatch")
                && call.desc.equals("(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;")));
        var embeddium = read("org/embeddedt/embeddium/impl/render/chunk/DefaultChunkRenderer");
        var fill = embeddium.methods.stream().filter(m -> m.name.equals("fillCommandBuffer")).findFirst().orElseThrow();
        var firstCall = java.util.stream.StreamSupport.stream(fill.instructions.spliterator(), false)
            .filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i).findFirst().orElseThrow();
        assertEquals("clear", firstCall.name);
        assertEquals("org/embeddedt/embeddium/impl/gl/device/MultiDrawBatch", firstCall.owner);
    }

    private static void prepare(ChunkRenderList list, int frame, int... sectionX) {
        list.reset(frame, true);
        for (int x : sectionX) list.add(LocalSectionIndex.pack(x, 1, 1), RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY);
    }

    private static void fill(MultiDrawBatch batch, int size) {
        batch.size = size;
        batch.isFilled = true;
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = SodiumRenderBatchOwnershipTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
