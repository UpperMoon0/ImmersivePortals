package qouteall.imm_ptl.core.compat.mixin.neoculus;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NeOculus retains an older cache that its texture lifecycle hooks never update. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pbr.TextureInfoCache", remap = false)
public class MixinNeOculusTextureInfoCache {
    @Shadow private Int2ObjectMap<?> cache;

    @Inject(method = "getInfo", at = @At("HEAD"))
    private void ip_discardUntrackedTextureInfo(int texture, CallbackInfoReturnable<Object> ci) {
        // The pipeline reads this legacy cache, while allocation/deletion hooks update
        // net.irisshaders.iris.texture.TextureInfoCache. A reused GL name can therefore
        // still describe a deleted color or depth texture after a shader toggle.
        // Let the original method fetch current storage instead of trusting that entry.
        cache.remove(texture);
    }
}
