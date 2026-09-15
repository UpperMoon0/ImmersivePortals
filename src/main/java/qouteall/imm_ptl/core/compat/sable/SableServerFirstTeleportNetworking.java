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
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.UUID;

/** Explicit request/prepare/acknowledgement protocol for server-first Sable rider teleports. */
public final class SableServerFirstTeleportNetworking {
    /**
     * Server networking and Sable physics both run on the server thread. Mark a client-request
     * scope so a Sable migration triggered from inside onPlayerTeleportedInClient does not start
     * a second server-initiated handoff for the same crossing.
     */
    private static final ThreadLocal<Request> ACTIVE_CLIENT_REQUEST = new ThreadLocal<>();

    private SableServerFirstTeleportNetworking() {}

    public record Request(
        int dimensionId, Vec3 eyePosBeforeTeleportation, UUID portalId, UUID handoffId
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
                buffer.readUUID(),
                buffer.readUUID()
            );
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(dimensionId);
            buffer.writeDouble(eyePosBeforeTeleportation.x);
            buffer.writeDouble(eyePosBeforeTeleportation.y);
            buffer.writeDouble(eyePosBeforeTeleportation.z);
            buffer.writeUUID(portalId);
            buffer.writeUUID(handoffId);
        }

        public void handle(ServerPayloadContext context) {
            ServerPlayer player = context.player();
            ServerTeleportationManager manager = ServerTeleportationManager.of(player.server);
            ResourceKey<Level> sourceDimension = PortalAPI.serverIntToDimKey(
                player.server, dimensionId
            );

            if (player.getRemovalReason() != null) {
                sendAck(player, handoffId, portalId, null, false, false);
                return;
            }

            Portal portal = findPortal(player, sourceDimension, portalId);
            if (portal == null) {
                // The deferred client has not moved. Still send an authoritative correction
                // before the negative acknowledgement so its pending gate cannot become sticky.
                manager.forceTeleportPlayer(
                    player, player.serverLevel().dimension(), player.position(), true
                );
                sendAck(player, handoffId, portalId, null, false, false);
                return;
            }

            // Physics already sent Prepare, the authoritative move, and Ack. A client
            // request that was in flight must not emit another position/yaw correction
            // after that terminal Ack or overwrite the camera we just transformed.
            if (acknowledgeAlreadyMigrated(player, portal)) return;

            ResourceKey<Level> destinationDimension = portal.getDestDim();
            ACTIVE_CLIENT_REQUEST.set(this);
            try {
                manager.onPlayerTeleportedInClient(
                    player, sourceDimension, eyePosBeforeTeleportation, portalId
                );
            }
            finally {
                ACTIVE_CLIENT_REQUEST.remove();
            }

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

            // Deliberately after the complete IP server path. If this request caused the Sable
            // migration, the migration hook saw ACTIVE_CLIENT_REQUEST and did not create a second
            // server-initiated handoff.
            sendAck(player, handoffId, portalId, portal, success, false);
        }

        private boolean acknowledgeAlreadyMigrated(ServerPlayer player, Portal portal) {
            if (!SableDimensionStackCompat.isRiderAlreadyMigrated(player, portal)) return false;
            sendAck(player, handoffId, portalId, portal, true, false);
            return true;
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Physics-first migrations announce their authoritative transform before moving the player.
     * The following vanilla/IP cross-dimension position packet and terminal Ack are therefore
     * ordered around one server-generated handoff id on the same connection.
     */
    public record Prepare(
        UUID handoffId,
        UUID portalId,
        int destinationDimensionId,
        boolean hasRotation,
        double rotationX,
        double rotationY,
        double rotationZ,
        double rotationW,
        boolean teleportChangesGravity
    ) implements CustomPacketPayload {
        public static final Type<Prepare> TYPE = new Type<>(
            ResourceLocation.parse("imm_ptl:sable_server_first_prepare")
        );
        public static final StreamCodec<FriendlyByteBuf, Prepare> CODEC = StreamCodec.of(
            (buffer, packet) -> packet.write(buffer), Prepare::read
        );

        private static Prepare read(FriendlyByteBuf buffer) {
            UUID handoffId = buffer.readUUID();
            UUID portalId = buffer.readUUID();
            int destinationDimensionId = buffer.readVarInt();
            boolean hasRotation = buffer.readBoolean();
            double rotationX = 0.0;
            double rotationY = 0.0;
            double rotationZ = 0.0;
            double rotationW = 1.0;
            if (hasRotation) {
                rotationX = buffer.readDouble();
                rotationY = buffer.readDouble();
                rotationZ = buffer.readDouble();
                rotationW = buffer.readDouble();
            }
            boolean teleportChangesGravity = buffer.readBoolean();
            return new Prepare(
                handoffId, portalId, destinationDimensionId,
                hasRotation, rotationX, rotationY, rotationZ, rotationW, teleportChangesGravity
            );
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(handoffId);
            buffer.writeUUID(portalId);
            buffer.writeVarInt(destinationDimensionId);
            buffer.writeBoolean(hasRotation);
            if (hasRotation) {
                buffer.writeDouble(rotationX);
                buffer.writeDouble(rotationY);
                buffer.writeDouble(rotationZ);
                buffer.writeDouble(rotationW);
            }
            buffer.writeBoolean(teleportChangesGravity);
        }

        public void handle() {
            ResourceKey<Level> destinationDimension = PortalAPI.clientIntToDimKey(destinationDimensionId);
            DQuaternion rotation = hasRotation
                ? new DQuaternion(rotationX, rotationY, rotationZ, rotationW)
                : null;
            SableServerFirstClientHandoff.prepareServerInitiated(
                handoffId, portalId, destinationDimension, rotation, teleportChangesGravity
            );
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Ack(
        UUID handoffId,
        UUID portalId,
        boolean serverInitiated,
        boolean success,
        int destinationDimensionId,
        boolean hasRotation,
        double rotationX,
        double rotationY,
        double rotationZ,
        double rotationW,
        boolean teleportChangesGravity
    ) implements CustomPacketPayload {
        public static final Type<Ack> TYPE = new Type<>(
            ResourceLocation.parse("imm_ptl:sable_server_first_ack")
        );
        public static final StreamCodec<FriendlyByteBuf, Ack> CODEC = StreamCodec.of(
            (buffer, packet) -> packet.write(buffer), Ack::read
        );

        private static Ack read(FriendlyByteBuf buffer) {
            UUID handoffId = buffer.readUUID();
            UUID portalId = buffer.readUUID();
            boolean serverInitiated = buffer.readBoolean();
            boolean success = buffer.readBoolean();
            int destinationDimensionId = buffer.readVarInt();
            boolean hasRotation = buffer.readBoolean();
            double rotationX = 0.0;
            double rotationY = 0.0;
            double rotationZ = 0.0;
            double rotationW = 1.0;
            if (hasRotation) {
                rotationX = buffer.readDouble();
                rotationY = buffer.readDouble();
                rotationZ = buffer.readDouble();
                rotationW = buffer.readDouble();
            }
            boolean teleportChangesGravity = buffer.readBoolean();
            return new Ack(
                handoffId, portalId, serverInitiated, success, destinationDimensionId,
                hasRotation, rotationX, rotationY, rotationZ, rotationW, teleportChangesGravity
            );
        }

        private void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(handoffId);
            buffer.writeUUID(portalId);
            buffer.writeBoolean(serverInitiated);
            buffer.writeBoolean(success);
            buffer.writeVarInt(destinationDimensionId);
            buffer.writeBoolean(hasRotation);
            if (hasRotation) {
                buffer.writeDouble(rotationX);
                buffer.writeDouble(rotationY);
                buffer.writeDouble(rotationZ);
                buffer.writeDouble(rotationW);
            }
            buffer.writeBoolean(teleportChangesGravity);
        }

        public void handle() {
            ResourceKey<Level> destinationDimension = PortalAPI.clientIntToDimKey(destinationDimensionId);
            DQuaternion rotation = hasRotation
                ? new DQuaternion(rotationX, rotationY, rotationZ, rotationW)
                : null;
            SableServerFirstClientHandoff.acknowledge(
                handoffId,
                portalId,
                serverInitiated,
                success,
                destinationDimension,
                rotation,
                teleportChangesGravity
            );
        }

        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * @return the active client-request handoff when this exact portal migration was triggered
     * from inside the request handler, otherwise {@code null}. A non-null result means the outer
     * request handler owns the terminal transform acknowledgement.
     */
    public static @Nullable UUID getActiveClientRequestHandoffId(UUID portalId) {
        Request request = ACTIVE_CLIENT_REQUEST.get();
        return request != null && request.portalId().equals(portalId)
            ? request.handoffId()
            : null;
    }

    /** Send transform context before a pure physics-first migration moves the player. */
    public static void sendServerInitiatedPrepare(
        ServerPlayer player, UUID handoffId, Portal portal
    ) {
        DQuaternion rotation = portal.getRotation();
        PacketDistributor.sendToPlayer(player, new Prepare(
            handoffId,
            portal.getUUID(),
            PortalAPI.serverDimKeyToInt(player.server, portal.getDestDim()),
            rotation != null,
            rotation == null ? 0.0 : rotation.x,
            rotation == null ? 0.0 : rotation.y,
            rotation == null ? 0.0 : rotation.z,
            rotation == null ? 1.0 : rotation.w,
            portal.getTeleportChangesGravity()
        ));
    }

    /** Send the terminal acknowledgement for a pure physics-first Sable migration. */
    public static void sendServerInitiatedAck(
        ServerPlayer player, UUID handoffId, Portal portal, boolean success
    ) {
        sendAck(player, handoffId, portal.getUUID(), portal, success, true);
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

    private static void sendAck(
        ServerPlayer player,
        UUID handoffId,
        UUID portalId,
        @Nullable Portal portal,
        boolean success,
        boolean serverInitiated
    ) {
        ResourceKey<Level> destinationDimension = portal != null
            ? portal.getDestDim()
            : player.serverLevel().dimension();
        DQuaternion rotation = portal != null ? portal.getRotation() : null;

        PacketDistributor.sendToPlayer(player, new Ack(
            handoffId,
            portalId,
            serverInitiated,
            success,
            PortalAPI.serverDimKeyToInt(player.server, destinationDimension),
            rotation != null,
            rotation == null ? 0.0 : rotation.x,
            rotation == null ? 0.0 : rotation.y,
            rotation == null ? 0.0 : rotation.z,
            rotation == null ? 1.0 : rotation.w,
            portal != null && portal.getTeleportChangesGravity()
        ));
    }
}
