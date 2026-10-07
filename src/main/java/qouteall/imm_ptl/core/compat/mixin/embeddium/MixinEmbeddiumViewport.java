package qouteall.imm_ptl.core.compat.mixin.embeddium;

import net.minecraft.core.SectionPos;
import org.embeddedt.embeddium.impl.render.viewport.Viewport;
import org.embeddedt.embeddium.impl.render.viewport.frustum.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.embeddium_compatibility.EmbeddiumInterface;
import qouteall.imm_ptl.core.compat.embeddium_compatibility.IEEmbeddiumViewport;

@Mixin(value = Viewport.class, remap = false)
public class MixinEmbeddiumViewport implements IEEmbeddiumViewport {
    @Unique private SectionPos ip_searchOrigin;

    @Override
    public void ip_setSearchOrigin(SectionPos origin) { ip_searchOrigin = origin; }

    @Inject(method = "getChunkCoord", at = @At("HEAD"), cancellable = true)
    private void ip_usePortalOrigin(CallbackInfoReturnable<SectionPos> cir) {
        if (ip_searchOrigin != null) cir.setReturnValue(ip_searchOrigin);
    }

    // Both overloads reach testAab, with camera-relative coordinates and actual model padding.
    @Redirect(method = {"isBoxVisible(Lnet/minecraft/world/phys/AABB;)Z", "isBoxVisible(IIIFFF)Z"},
        at = @At(value = "INVOKE", target = "Lorg/embeddedt/embeddium/impl/render/viewport/frustum/Frustum;testAab(FFFFFF)Z"))
    private boolean ip_testPortalFrustum(Frustum frustum, float minX, float minY, float minZ,
                                         float maxX, float maxY, float maxZ) {
        if (!frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ)) return false;
        var culler = EmbeddiumInterface.frustumCuller;
        return culler == null || !culler.canDetermineInvisibleWithCameraCoord(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
