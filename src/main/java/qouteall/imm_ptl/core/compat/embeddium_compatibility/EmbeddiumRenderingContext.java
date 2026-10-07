package qouteall.imm_ptl.core.compat.embeddium_compatibility;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.embeddedt.embeddium.impl.render.chunk.ChunkUpdateType;
import org.embeddedt.embeddium.impl.render.chunk.RenderSection;
import org.embeddedt.embeddium.impl.render.chunk.lists.SortedRenderLists;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import qouteall.imm_ptl.core.render.FrustumCuller;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;

/** Per-recursion state; chunk meshes and upload queues remain owned by the dimension renderer. */
public final class EmbeddiumRenderingContext {
    public SortedRenderLists renderLists = SortedRenderLists.empty();
    public Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists = new EnumMap<>(ChunkUpdateType.class);
    public int renderDistance;
    public int lastUpdatedFrame;
    public BlockPos lastCameraPosition;
    public Vec3 cameraPosition = Vec3.ZERO;
    public Viewport viewport;
    public FrustumCuller frustumCuller;

    public EmbeddiumRenderingContext(int renderDistance) {
        if (renderDistance <= 0) throw new IllegalArgumentException("Render distance must be positive");
        this.renderDistance = renderDistance;
        for (ChunkUpdateType type : ChunkUpdateType.values()) rebuildLists.put(type, new ArrayDeque<>());
    }
}
