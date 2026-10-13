package qouteall.imm_ptl.core.compat.mixin.sable;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable.SableServerFirstClientHandoff;

/** Do not simulate an on-foot fall between the ordered detach and seat-attachment packets. */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayer_SableRiderMovementHold {
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void ip_holdDetachedRiderMotion(CallbackInfo ci) {
        if (SableServerFirstClientHandoff.shouldHoldDetachedMovement((LocalPlayer) (Object) this)) {
            // Keep ticking the client/network normally. Only local movement/input prediction is
            // suspended; authoritative position packets can still change dimension and position.
            // No velocity, gravity attribute, flight flag or player position is overwritten.
            ci.cancel();
        }
    }
}
