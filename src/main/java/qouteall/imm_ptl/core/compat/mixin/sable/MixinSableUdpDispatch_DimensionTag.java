package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.network.udp.SableUDPPacket;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable.SableSnapshotClientRouting;

/** Includes the integrated-server loopback path, which serializes but bypasses Netty. */
@Mixin(targets = {
    "dev.ryanhcode.sable.network.udp.handler.SableUDPChannelHandlerClient",
    "dev.ryanhcode.sable.network.udp.SableUDPServer"
}, remap = false)
public abstract class MixinSableUdpDispatch_DimensionTag {
    @WrapOperation(method = "*", at = @At(value = "INVOKE",
        target = "Ldev/ryanhcode/sable/network/udp/SableUDPPacket;handleClient(Lnet/minecraft/world/level/Level;)V"), require = 1)
    private static void ip_dispatchAddressedSnapshot(SableUDPPacket packet, Level currentLevel, Operation<Void> original) {
        SableSnapshotClientRouting.dispatch(packet, currentLevel, original);
    }
}
