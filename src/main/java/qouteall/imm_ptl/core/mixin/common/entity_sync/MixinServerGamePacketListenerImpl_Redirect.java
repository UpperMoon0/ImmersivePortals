package qouteall.imm_ptl.core.mixin.common.entity_sync;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;

@Mixin(ServerCommonPacketListenerImpl.class)
public class MixinServerGamePacketListenerImpl_Redirect {
    @Shadow @Final protected MinecraftServer server;
    
    @SuppressWarnings({"rawtypes", "unchecked"})
    @ModifyVariable(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Packet modifyPacket(Packet originalPacket) {
        // Sable sends mount corrections directly, bypassing vanilla teleport().
        // Fill the required wire metadata before redirection can encode the packet.
        if (originalPacket instanceof ClientboundPlayerPositionPacket
            && (Object) this instanceof ServerGamePacketListenerImpl gameListener) {
            IEPlayerPositionLookS2CPacket positionPacket = (IEPlayerPositionLookS2CPacket) originalPacket;
            if (positionPacket.ip_getPlayerDimension() == null) {
                positionPacket.ip_setPlayerDimension(gameListener.player.level().dimension());
            }
        }
        if (PacketRedirection.getForceRedirectDimension() == null) {
            return originalPacket;
        }
        
        return PacketRedirection.createRedirectedMessage(
            server,
            PacketRedirection.getForceRedirectDimension(),
            originalPacket
        );
    }
    
    @SuppressWarnings("unchecked")
    @Inject(
        method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V"
        ),
        cancellable = true
    )
    private void onSend(
        Packet<?> packet, @Nullable PacketSendListener packetSendListener, CallbackInfo ci
    ) {
        PacketRedirection.ForceBundleCallback forceBundleCallback = PacketRedirection.getForceBundleCallback();
        if (forceBundleCallback != null) {
            forceBundleCallback.accept(
                (ServerCommonPacketListenerImpl) (Object) this,
                (Packet<ClientGamePacketListener>) packet
            );
            ci.cancel();
        }
    }
}
