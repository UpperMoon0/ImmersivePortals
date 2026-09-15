package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.network.tcp.SableTCPPacket;
import dev.ryanhcode.sable.network.udp.SableUDPPacket;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.Collection;
import java.util.UUID;

@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public abstract class MixinSubLevelTrackingSystem_SablePortalCompat {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "shouldLoad", at = @At("RETURN"), cancellable = true)
    private void ip_includePortalWatchers(
        Player player, Vector3dc position, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        boolean portalWatching = ImmPtlChunkTracking.isPlayerWatchingChunk(
            serverPlayer,
            level.dimension(),
            ((int) Math.floor(position.x())) >> 4,
            ((int) Math.floor(position.z())) >> 4
        );

        if (serverPlayer.serverLevel() != level) {
            cir.setReturnValue(portalWatching);
        }
        else if (!cir.getReturnValue()) {
            cir.setReturnValue(portalWatching);
        }
    }

    @Inject(method = "collectPlayers", at = @At("TAIL"))
    private void ip_collectPortalWatchers(
        Vector3d position, Collection<UUID> tracking, CallbackInfo ci
    ) {
        int chunkX = ((int) Math.floor(position.x())) >> 4;
        int chunkZ = ((int) Math.floor(position.z())) >> 4;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (ImmPtlChunkTracking.isPlayerWatchingChunk(player, level.dimension(), chunkX, chunkZ)) {
                tracking.add(player.getUUID());
            }
        }
    }

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"),
        require = 2
    )
    private Player ip_findPortalWatcherDuringTracking(ServerLevel queriedLevel, UUID uuid) {
        Player globalPlayer = level.getServer().getPlayerList().getPlayer(uuid);
        return globalPlayer != null ? globalPlayer : queriedLevel.getPlayerByUUID(uuid);
    }

    @Redirect(
        method = "sendMovementUpdates",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"),
        require = 2
    )
    private Player ip_findPortalWatcherDuringMovement(ServerLevel queriedLevel, UUID uuid) {
        Player globalPlayer = level.getServer().getPlayerList().getPlayer(uuid);
        return globalPlayer != null ? globalPlayer : queriedLevel.getPlayerByUUID(uuid);
    }

    @Inject(method = "onSubLevelRemoved", at = @At("HEAD"), cancellable = true)
    private void ip_delaySourceRemoval(
        SubLevel subLevel, SubLevelRemovalReason reason, CallbackInfo ci
    ) {
        if (subLevel instanceof ServerSubLevel serverSubLevel
            && SableDimensionStackCompat.shouldSuppressSourceRemoval(level, serverSubLevel, reason)) {
            ci.cancel();
        }
    }

    /** The compat bridge pre-sends the destination once; discard Sable's queued copy. */
    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;sendFullSync(Lnet/minecraft/server/level/ServerPlayer;Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"
        )
    )
    private void ip_avoidDuplicateDestinationFullSync(
        SubLevelTrackingSystem trackingSystem,
        ServerPlayer player,
        ServerSubLevel subLevel,
        @Nullable CustomPacketPayload extraPacket,
        Operation<Void> original
    ) {
        if (!SableDimensionStackCompat.shouldSkipDuplicateDestinationFullSync(
            level, player, subLevel
        )) {
            original.call(trackingSystem, player, subLevel, extraPacket);
        }
    }

    @Inject(method = "sendFullSync", at = @At("RETURN"))
    private void ip_finishDestinationHandoff(
        ServerPlayer player,
        ServerSubLevel subLevel,
        @Nullable CustomPacketPayload extraPacket,
        CallbackInfo ci
    ) {
        SableDimensionStackCompat.onDestinationFullSync(level, player, subLevel);
    }

    /** Remote Sable UDP has no dimension envelope; use redirected TCP for portal watchers. */
    @WrapOperation(
        method = "sendMovementUpdates",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/network/udp/SableUDPServer;sendUDPPacket(Lnet/minecraft/server/level/ServerPlayer;Ldev/ryanhcode/sable/network/udp/SableUDPPacket;Z)Z"
        )
    )
    private boolean ip_routeRemoteSableUdpThroughRedirectedTcp(
        SableUDPServer udpServer,
        ServerPlayer player,
        SableUDPPacket packet,
        boolean flush,
        Operation<Boolean> original
    ) {
        if (player.serverLevel() != level && packet instanceof SableTCPPacket tcpPacket) {
            PacketRedirection.withForceRedirect(level, () -> player.connection.send(
                new ClientboundCustomPayloadPacket(tcpPacket)
            ));
            return true;
        }
        return original.call(udpServer, player, packet, flush);
    }

    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V")
    )
    private void ip_redirectSablePacket(
        ServerGamePacketListenerImpl connection, Packet<?> packet, Operation<Void> original
    ) {
        PacketRedirection.withForceRedirect(level, () -> original.call(connection, packet));
    }
}
