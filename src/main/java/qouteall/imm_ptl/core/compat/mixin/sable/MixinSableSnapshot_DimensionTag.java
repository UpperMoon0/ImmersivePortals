package qouteall.imm_ptl.core.compat.mixin.sable;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable.SableDimensionTaggedSnapshot;
import qouteall.imm_ptl.core.compat.sable.SableSnapshotProtocol;

@Mixin(targets = {
    "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket",
    "dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket"
}, remap = false)
public abstract class MixinSableSnapshot_DimensionTag implements SableDimensionTaggedSnapshot {
    @Unique private ResourceKey<Level> ip_snapshotDimension;
    @Override public void ip_setSnapshotDimension(ResourceKey<Level> dimension) { ip_snapshotDimension = dimension; }
    @Override public ResourceKey<Level> ip_getSnapshotDimension() { return ip_snapshotDimension; }

    @Inject(method = "handle", at = @At("HEAD"), require = 1)
    private void ip_recordOrderedTransport(foundry.veil.api.network.handler.PacketContext context, CallbackInfo ci) {
        if ((Object) this instanceof dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket) {
            SableSnapshotProtocol.recordTcpSnapshot();
        }
    }

    @Inject(method = "encode", at = @At("RETURN"), require = 1)
    private void ip_writeDimension(ByteBuf buffer, CallbackInfo ci) {
        SableSnapshotProtocol.write((FriendlyByteBuf) buffer, ip_snapshotDimension);
    }

    @Inject(method = "<init>(Lio/netty/buffer/ByteBuf;)V", at = @At("RETURN"), require = 1)
    private void ip_readDimension(ByteBuf buffer, CallbackInfo ci) {
        ip_snapshotDimension = SableSnapshotProtocol.read((FriendlyByteBuf) buffer);
    }
}
