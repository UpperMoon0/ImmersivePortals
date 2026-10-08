package qouteall.imm_ptl.core.compat.mixin.neoculus;

import net.irisshaders.iris.pbr.TextureInfoCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.IntBuffer;

/** NeOculus retains an older cache that its texture lifecycle hooks never update. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.texture.TextureInfoCache", remap = false)
public class MixinNeOculusTextureInfoCache {
    @Inject(method = "onTexImage2D", at = @At("HEAD"))
    private void ip_updateLegacyTextureInfo(int target, int level, int internalFormat,
        int width, int height, int border, int format, int type, IntBuffer pixels, CallbackInfo ci) {
        // Mirror the lifecycle event into the older cache still read by the pipeline
        // and gtextureSize. Ordinary lookups retain their cached format and dimensions.
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalFormat,
            width, height, border, format, type, pixels);
    }

    @Inject(method = "onDeleteTexture", at = @At("HEAD"))
    private void ip_deleteLegacyTextureInfo(int texture, CallbackInfo ci) {
        TextureInfoCache.INSTANCE.onDeleteTexture(texture);
    }
}
