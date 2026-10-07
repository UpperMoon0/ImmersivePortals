package qouteall.imm_ptl.core.chunk_loading;

import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** Execute the real optional renderer trackers; no fabricated "ready" flags or world/GPU mocks. */
class NestedPortalRendererReadinessTest {
    @Test
    void embeddiumReceivesAllMeshNeighborsOfTheNestedBoundaryChunks() throws Exception {
        checkTracker("org.embeddedt.embeddium.impl.render.chunk.map.", 1);
        checkTracker("org.embeddedt.embeddium.impl.render.chunk.map.", 2);
    }

    @Test
    void sodiumReceivesAllMeshNeighborsOfTheNestedBoundaryChunks() throws Exception {
        checkTracker("net.caffeinemc.mods.sodium.client.render.chunk.map.", 1);
        checkTracker("net.caffeinemc.mods.sodium.client.render.chunk.map.", 2);
    }

    @Test
    void everyBaseLoaderBranchAddsTheDataHaloAfterItsVisibilityCalculation() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
            "qouteall/imm_ptl/core/chunk_loading/ChunkVisibility.class")) {
            assertNotNull(input);
            var target = new ClassNode();
            new ClassReader(input).accept(target, 0);
            for (String name : new String[]{"playerDirectLoader", "getGeneralDirectPortalLoader", "getGeneralPortalIndirectLoader"}) {
                var method = target.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
                int haloCalls = 0, loaders = 0;
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        if (call.owner.equals(target.name) && call.name.equals("getChunkDataLoadingRadius")) haloCalls++;
                        if (call.owner.equals("qouteall/imm_ptl/core/chunk_loading/ChunkLoader") && call.name.equals("<init>")) loaders++;
                    }
                }
                assertEquals(name.equals("playerDirectLoader") ? 1 : 2, loaders, name);
                assertEquals(loaders, haloCalls, name + " must pad every direct/global/nested branch");
            }
        }
    }

    private static void checkTracker(String packageName, int visibleRadius) throws Exception {
        Class<?> type = Class.forName(packageName + "ChunkTracker");
        Object tracker = type.getConstructor().newInstance();
        Method added = type.getMethod("onChunkStatusAdded", int.class, int.class, int.class);
        Method removed = type.getMethod("onChunkStatusRemoved", int.class, int.class, int.class);
        LongCollection ready = (LongCollection) type.getMethod("getReadyChunks").invoke(tracker);
        Class<?> status = Class.forName(packageName + "ChunkStatus");
        int blocks = status.getField("FLAG_HAS_BLOCK_DATA").getInt(null);
        int light = status.getField("FLAG_HAS_LIGHT_DATA").getInt(null);

        for (int x = -visibleRadius; x <= visibleRadius; x++) {
            for (int z = -visibleRadius; z <= visibleRadius; z++) {
                added.invoke(tracker, x, z, blocks | light);
            }
        }
        assertTrue(ready.contains(ChunkPos.asLong(0, 0)));
        // Radius1 reproduces the nested wall at chunks(-1,-1)/(0,-1). Radius2 also
        // covers the oblique particle backdrop missing chunk(-2,-1) in actual CI.
        assertFalse(ready.contains(ChunkPos.asLong(-visibleRadius, -1)));
        assertFalse(ready.contains(ChunkPos.asLong(0, -visibleRadius)));

        int dataRadius = ChunkVisibility.getChunkDataLoadingRadius(visibleRadius);
        for (int x = -dataRadius; x <= dataRadius; x++) {
            for (int z = -dataRadius; z <= dataRadius; z++) {
                added.invoke(tracker, x, z, blocks);
            }
        }
        assertFalse(ready.contains(ChunkPos.asLong(0, -visibleRadius)), "The halo must include real light data too");
        for (int x = -dataRadius; x <= dataRadius; x++) {
            for (int z = -dataRadius; z <= dataRadius; z++) {
                added.invoke(tracker, x, z, light);
            }
        }
        for (int x = -visibleRadius; x <= visibleRadius; x++) {
            for (int z = -visibleRadius; z <= visibleRadius; z++) {
                assertTrue(ready.contains(ChunkPos.asLong(x, z)), packageName + "not ready: " + x + "," + z);
            }
        }
        // Preserve the backend's safety invariant when a required neighbor unloads.
        removed.invoke(tracker, 0, -dataRadius, light);
        assertFalse(ready.contains(ChunkPos.asLong(0, -visibleRadius)));
        added.invoke(tracker, 0, -dataRadius, light);
        assertTrue(ready.contains(ChunkPos.asLong(0, -visibleRadius)));
    }
}
