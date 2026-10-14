package qouteall.imm_ptl.core.compat.mixin.sable;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable.SableSectionRenderOwner;

@Mixin(value=VanillaChunkedSubLevelRenderData.class,remap=false)
public abstract class MixinVanillaChunkedSubLevelRenderData_SableSectionOwnership {
    @Shadow @Final private ClientSubLevel subLevel;
    @Shadow @Final private ObjectList<SectionRenderDispatcher.RenderSection> allRenderSections;
    @Unique private SableSectionRenderOwner ip_sectionOwner;

    @Inject(method="resize",at=@At("HEAD"))
    private void ip_beginResize(CallbackInfo ci) {
        if (ip_sectionOwner!=null) ip_sectionOwner.beginResize();
    }

    @Inject(method="resize",at=@At("RETURN"))
    private void ip_ownSections(CallbackInfo ci) {
        if (ip_sectionOwner==null) ip_sectionOwner=new SableSectionRenderOwner(subLevel);
        ip_sectionOwner.refresh(allRenderSections);
    }

    @Inject(method="close",at=@At("HEAD"))
    private void ip_releaseOwnership(CallbackInfo ci) {
        if (ip_sectionOwner!=null) ip_sectionOwner.close();
    }
}
