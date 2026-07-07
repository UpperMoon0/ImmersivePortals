package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
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
        if (!cir.getReturnValue() && player instanceof ServerPlayer serverPlayer) {
            cir.setReturnValue(ImmPtlChunkTracking.isPlayerWatchingChunk(
                serverPlayer,
                level.dimension(),
                ((int) Math.floor(position.x())) >> 4,
                ((int) Math.floor(position.z())) >> 4
            ));
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
    private Player ip_findPortalWatcher(ServerLevel ignored, UUID uuid) {
        return level.getServer().getPlayerList().getPlayer(uuid);
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
