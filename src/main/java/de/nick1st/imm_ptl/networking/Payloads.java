package de.nick1st.imm_ptl.networking;

import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import qouteall.imm_ptl.core.compat.sable.SableServerFirstTeleportNetworking;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.platform_specific.IPModEntry;

public class Payloads {
    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(IPModEntry.MODID);

        // Configuration
        registrar.configurationToClient(ImmPtlNetworkConfig.S2CConfigStartPacket.TYPE,
                ImmPtlNetworkConfig.S2CConfigStartPacket.CODEC,
                ImmPtlNetworkConfig.S2CConfigStartPacket::handle);

        registrar.configurationToServer(ImmPtlNetworkConfig.C2SConfigCompletePacket.TYPE,
                ImmPtlNetworkConfig.C2SConfigCompletePacket.CODEC,
                ImmPtlNetworkConfig.C2SConfigCompletePacket::handle);

        // Play
        registrar.playToServer(ImmPtlNetworking.TeleportPacket.TYPE, ImmPtlNetworking.TeleportPacket.CODEC, ((teleportPacket, iPayloadContext) -> teleportPacket.handle((ServerPayloadContext) iPayloadContext)));

        registrar.playToServer(
                SableServerFirstTeleportNetworking.Request.TYPE,
                SableServerFirstTeleportNetworking.Request.CODEC,
                (request, context) -> request.handle((ServerPayloadContext) context));

        registrar.playToClient(
                SableServerFirstTeleportNetworking.Prepare.TYPE,
                SableServerFirstTeleportNetworking.Prepare.CODEC,
                (prepare, context) -> prepare.handle());

        registrar.playToClient(
                SableServerFirstTeleportNetworking.Ack.TYPE,
                SableServerFirstTeleportNetworking.Ack.CODEC,
                (ack, context) -> ack.handle());

        registrar.playToClient(ImmPtlNetworking.GlobalPortalSyncPacket.TYPE, ImmPtlNetworking.GlobalPortalSyncPacket.CODEC, (globalPortalSyncPacket, iPayloadContext) -> globalPortalSyncPacket.handle());

        registrar.playToClient(ImmPtlNetworking.PortalSyncPacket.TYPE, ImmPtlNetworking.PortalSyncPacket.CODEC, (p, c) ->
                p.handle());

        final PayloadRegistrar redirector = event.registrar("i");
        redirector.playToClient(PacketRedirection.Payload.TYPE, PacketRedirection.Payload.CODEC, (p, c) -> p.handle((ClientGamePacketListener) c.listener()));
    }
}
