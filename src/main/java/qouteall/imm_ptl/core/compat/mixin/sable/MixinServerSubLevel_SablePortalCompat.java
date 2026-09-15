package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.UUID;

/**
 * ServerSubLevel.playerSink() is used by movement-adjacent Sable updates such as bounds and
 * display-name changes. Those sends live outside SubLevelTrackingSystem, so they need the same
 * server-wide player lookup and IP dimension redirection as the main tracking pipeline.
 */
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class MixinServerSubLevel_SablePortalCompat {
    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;")
    )
    private Player ip_findPortalWatcher(
        ServerLevel level, UUID uuid, Operation<Player> original
    ) {
        Player globalPlayer = level.getServer().getPlayerList().getPlayer(uuid);
        return globalPlayer != null ? globalPlayer : original.call(level, uuid);
    }

    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V")
    )
    private void ip_redirectSubLevelPacket(
        ServerGamePacketListenerImpl connection, Packet<?> packet, Operation<Void> original
    ) {
        ServerLevel level = ((ServerSubLevel) (Object) this).getLevel();
        PacketRedirection.withForceRedirect(level, () -> original.call(connection, packet));
    }
}
