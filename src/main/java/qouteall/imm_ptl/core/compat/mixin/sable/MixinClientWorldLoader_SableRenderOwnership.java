package qouteall.imm_ptl.core.compat.mixin.sable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable.SableSectionRenderOwner;

@Mixin(value=ClientWorldLoader.class,remap=false)
public abstract class MixinClientWorldLoader_SableRenderOwnership {
    @Inject(method="cleanUp",at=@At("HEAD"))
    private static void ip_closeOwnersOnDisconnect(CallbackInfo ci) { SableSectionRenderOwner.closeAll(); }

    @Inject(method="disposeDimensionDynamically",at=@At("HEAD"))
    private static void ip_closeOwnersOnDimensionRemoval(ResourceKey<Level> dimension,CallbackInfo ci) {
        SableSectionRenderOwner.closeDimension(dimension);
    }
}
