package qouteall.imm_ptl.core.compat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the installed dependency bytecode, not a copy of the upstream API. */
class EmbeddiumTargetContractTest {
    private static final String IMPL = "org/embeddedt/embeddium/impl/";

    @Test
    void rendererAndRecursiveContextMatchPinnedEmbeddiumAbi() throws Exception {
        var renderer = read(IMPL + "render/EmbeddiumWorldRenderer");
        method(renderer, "setupTerrain", "(Lnet/minecraft/client/Camera;L" + IMPL + "render/viewport/Viewport;IZZ)V");
        field(renderer, "currentViewport", "L" + IMPL + "render/viewport/Viewport;");
        field(renderer, "renderSectionManager", "L" + IMPL + "render/chunk/RenderSectionManager;");
        var sections = read(IMPL + "render/chunk/RenderSectionManager");
        field(sections, "renderDistance", "I");
        field(sections, "lastUpdatedFrame", "I");
        field(sections, "renderLists", "L" + IMPL + "render/chunk/lists/SortedRenderLists;");
        field(sections, "rebuildLists", "Ljava/util/Map;");
        field(sections, "lastCameraPosition", "Lnet/minecraft/core/BlockPos;");
        field(sections, "cameraPosition", "Lnet/minecraft/world/phys/Vec3;");
        method(sections, "isSectionVisible", "(III)Z");
        var region = read(IMPL + "render/chunk/region/RenderRegion");
        field(region, "renderList", "L" + IMPL + "render/chunk/lists/ChunkRenderList;");
        method(region, "getRenderList", "()L" + IMPL + "render/chunk/lists/ChunkRenderList;");
    }

    @Test
    void cullingShaderAndChunkTrackingTargetsExist() throws Exception {
        var viewport = read(IMPL + "render/viewport/Viewport");
        method(viewport, "getChunkCoord", "()Lnet/minecraft/core/SectionPos;");
        for (String descriptor : List.of("(Lnet/minecraft/world/phys/AABB;)Z", "(IIIFFF)Z")) {
            var method = viewport.methods.stream().filter(m -> m.name.equals("isBoxVisible") && m.desc.equals(descriptor)).findFirst().orElseThrow();
            assertTrue(java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals("testAab") && call.desc.equals("(FFFFFF)Z")));
        }
        var culler = read(IMPL + "render/chunk/occlusion/OcclusionCuller");
        method(culler, "findVisible", "(L" + IMPL + "render/chunk/occlusion/OcclusionCuller$Visitor;L" + IMPL + "render/viewport/Viewport;FZI)V");
        method(culler, "getRenderSection", "(III)L" + IMPL + "render/chunk/RenderSection;");
        method(culler, "isWithinFrustum", "(L" + IMPL + "render/viewport/Viewport;L" + IMPL + "render/chunk/RenderSection;)Z");
        var shader = read(IMPL + "render/chunk/shader/ChunkShaderInterface");
        method(shader, "<init>", "(L" + IMPL + "render/chunk/shader/ShaderBindingContext;L" + IMPL + "render/chunk/shader/ChunkShaderOptions;)V");
        method(shader, "setupState", "()V");
        method(read(IMPL + "sodium/FlawlessFrames"), "isActive", "()Z");
        var tracker = read(IMPL + "render/chunk/map/ChunkTracker");
        method(tracker, "onChunkStatusAdded", "(III)V");
        method(tracker, "onChunkStatusRemoved", "(III)V");
        method(read(IMPL + "gl/shader/ShaderLoader"), "getShaderSource", "(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;");
    }

    @Test
    void rendererMixinsAreClientOnlyAndDoNotReferenceSodiumNamespaces() throws Exception {
        var config = JsonParser.parseString(resource("imm_ptl_compat.mixins.json")).getAsJsonObject();
        config.getAsJsonArray("mixins").forEach(entry -> assertFalse(entry.getAsString().startsWith("embeddium.")));
        long count = 0;
        for (var entry : config.getAsJsonArray("client")) {
            String name = entry.getAsString();
            if (!name.startsWith("embeddium.")) continue;
            count++;
            String contents = resource("qouteall/imm_ptl/core/compat/mixin/" + name.replace('.', '/') + ".class");
            assertFalse(contents.contains("net/caffeinemc/"), name + " references the wrong renderer");
        }
        assertEquals(9, count);
    }

    private static ClassNode read(String name) throws Exception {
        try (InputStream input = EmbeddiumTargetContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, "Missing pinned renderer dependency: " + name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = EmbeddiumTargetContractTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static void field(ClassNode node, String name, String descriptor) {
        assertTrue(node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor)), node.name + "." + name + ":" + descriptor);
    }

    private static void method(ClassNode node, String name, String descriptor) {
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)), node.name + "." + name + descriptor);
    }
}
