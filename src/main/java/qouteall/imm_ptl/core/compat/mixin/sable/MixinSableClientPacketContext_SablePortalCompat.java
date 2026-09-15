package qouteall.imm_ptl.core.compat.mixin.sable;

import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sable.SableClientPacketContext;

/** Makes redirected Sable payloads use the IP target world instead of the local player's world. */
@Mixin(
    targets = {
        "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket",
        "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeBoundsSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundChangeSubLevelNamePacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundFinalizeSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundRecentlySplitSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundStopMovingSubLevelPacket",
        "dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket"
    },
    remap = false
)
public abstract class MixinSableClientPacketContext_SablePortalCompat {
    @Redirect(
        method = "handle",
        at = @At(
            value = "INVOKE",
            target = "Lfoundry/veil/api/network/handler/PacketContext;level()Lnet/minecraft/world/level/Level;"
        )
    )
    private Level ip_useRedirectedLevel(PacketContext context) {
        return SableClientPacketContext.resolve(context);
    }
}
