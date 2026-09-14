package qouteall.imm_ptl.core.compat.sable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import org.jetbrains.annotations.NotNull;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.UUID;

/** Explicit request/acknowledgement protocol for client-deferred Sable rider teleports. */
public final class SableServerFirstTeleportNetworking {
    private SableServerFirstTeleportNetworking() {}

    public record Request(
        int dimensionId, Vec3 eyePosBeforeTeleportation, UUID portalId
    ) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(
            ResourceLocation.parse("imm_ptl:sable_server_first_teleport")
        );
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of(
            (buffer, packet) -> packet.write(buffer), Request::read
        );

        private static Request read(FriendlyByteBuf buffer) {
            return new Request(
                buffer.readVarInt(),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readUUID()
            );
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(dimensionId);
            buffer.writeDouble(eyePosBeforeTeleportation.x);
            buffer.writeDouble(eyePosBeforeTeleportation.y);
            buffer.writeDouble(eyePosBeforeTeleportation.z);
            buffer.writeUUID(portalId);
        }

        public void handle(ServerPayloadContext context) {
            ServerPlayer player = context.player();
            ServerTeleportationManager manager = ServerTeleportationManager.of(player.server);
            ResourceKey<Level> sourceDimension = PortalAPI.serverIntToDimKey(
                player.server, dimensionId
            );

            if (player.getRemovalReason() != null) {
                sendAck(player, portalId, false);
                return;
            }

            Portal portal = findPortal(player, sourceDimension, portalId);
            if (portal == null) {
                // The deferred client has not moved. Still send an authoritative correction
                // before the negative acknowledgement so its pending gate cannot become sticky.
                manager.forceTeleportPlayer(
                    player, player.serverLevel().dimension(), player.position(), true
                );
                sendAck(player, portalId, false);
                return;
            }

            ResourceKey<Level> destinationDimension = portal.getDestDim();
            manager.onPlayerTeleportedInClient(
                player, sourceDimension, eyePosBeforeTeleportation, portalId
            );

            boolean success = player.getRemovalReason() == null
                && player.serverLevel().dimension().equals(destinationDimension)
                && SableDimensionStackCompat.isRiderAlreadyMigrated(player, portal);

            if (!success && player.getRemovalReason() == null) {
                // Validation, migration, or another server-side guard rejected the handoff.
                // Reassert the actual server state before releasing the client's pending gate.
                manager.forceTeleportPlayer(
                    player, player.serverLevel().dimension(), player.position(), true
                );
            }

            sendAck(player, portalId, success);
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Ack(UUID portalId, boolean success) implements CustomPacketPayload {
        public static final Type<Ack> TYPE = new Type<>(
            ResourceLocation.parse("imm_ptl:sable_server_first_ack")
        );
        public static final StreamCodec<FriendlyByteBuf, Ack> CODEC = StreamCodec.of(
            (buffer, packet) -> packet.write(buffer), Ack::read
        );

        private static Ack read(FriendlyByteBuf buffer) {
            return new Ack(buffer.readUUID(), buffer.readBoolean());
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(portalId);
            buffer.writeBoolean(success);
        }

        public void handle() {
            SableServerFirstClientHandoff.acknowledge(portalId, success);
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static Portal findPortal(
        ServerPlayer player, ResourceKey<Level> sourceDimension, UUID portalId
    ) {
        ServerLevel sourceWorld = player.server.getLevel(sourceDimension);
        if (sourceWorld == null) return null;

        Entity entity = sourceWorld.getEntity(portalId);
        if (entity instanceof Portal portal) return portal;

        return GlobalPortalStorage.get(sourceWorld).data.stream()
            .filter(portal -> portal.getUUID().equals(portalId))
            .findFirst()
            .orElse(null);
    }

    private static void sendAck(ServerPlayer player, UUID portalId, boolean success) {
        PacketDistributor.sendToPlayer(player, new Ack(portalId, success));
    }
}
