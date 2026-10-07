package qouteall.imm_ptl.core.compat.mixin.embeddium;

import org.embeddedt.embeddium.impl.render.EmbeddiumWorldRenderer;
import org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = EmbeddiumWorldRenderer.class, remap = false)
public interface IEEmbeddiumWorldRenderer {
    @Accessor("renderSectionManager")
    RenderSectionManager ip_getRenderSectionManager();

    @Accessor("currentViewport")
    Viewport ip_getViewport();

    @Accessor("currentViewport")
    void ip_setViewport(Viewport viewport);
}
