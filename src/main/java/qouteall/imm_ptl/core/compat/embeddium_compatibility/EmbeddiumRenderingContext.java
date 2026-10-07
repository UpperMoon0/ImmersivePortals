package qouteall.imm_ptl.core.compat.embeddium_compatibility;

import org.embeddedt.embeddium.impl.render.chunk.lists.SortedRenderLists;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import qouteall.imm_ptl.core.render.FrustumCuller;

/** Per-recursion state; chunk meshes and upload queues remain owned by the dimension renderer. */
public final class EmbeddiumRenderingContext {
    public SortedRenderLists renderLists = SortedRenderLists.empty();
    public int renderDistance;
    public Viewport viewport;
    public FrustumCuller frustumCuller;

    public EmbeddiumRenderingContext(int renderDistance) {
        if (renderDistance <= 0) throw new IllegalArgumentException("Render distance must be positive");
        this.renderDistance = renderDistance;
    }
}
