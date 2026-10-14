package qouteall.imm_ptl.core.compat.mixin.sable;

import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable.SableServerFirstTeleportNetworking;
import qouteall.imm_ptl.core.compat.sable.SableSnapshotProtocol;

@Mixin(targets = "dev.ryanhcode.sable.network.packets.tcp.ClientboundSableUDPActivationPacket", remap = false)
public abstract class MixinSableUdpActivation_DimensionTag {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 1)
    private void ip_useOrderedFallbackForLegacyPortalServer(PacketContext context, CallbackInfo ci) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null && SableSnapshotProtocol.declineLegacyPortalUdp(
            connection.hasChannel(SableSnapshotProtocol.Capability.TYPE),
            connection.hasChannel(SableServerFirstTeleportNetworking.Prepare.TYPE))) {
            // No authentication means Sable retains its normal TCP fallback. Do not change config.
            com.mojang.logging.LogUtils.getLogger().info("Using dimension-redirected Sable TCP: peer lacks snapshot-dimension v1");
            ci.cancel();
        }
    }
}
