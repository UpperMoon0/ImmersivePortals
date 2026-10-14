package qouteall.imm_ptl.core.compat.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.network.udp.SableUDPPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ClientWorldLoader;

/** Render-thread dispatch for both network UDP and Sable's local UDP simulation. */
public final class SableSnapshotClientRouting {
    private SableSnapshotClientRouting() {}

    public static void dispatch(SableUDPPacket packet, Level currentLevel, Operation<Void> original) {
        if (!(packet instanceof SableDimensionTaggedSnapshot snapshot)) {
            original.call(packet, currentLevel); // Authentication/keepalive are not world snapshots.
            return;
        }
        var connection = Minecraft.getInstance().getConnection();
        boolean negotiated = connection != null && connection.hasChannel(SableSnapshotProtocol.Capability.TYPE);
        var dimension = snapshot.ip_getSnapshotDimension();
        if (dimension == null) {
            // A negotiated connection may never fall back to execution-time current-world routing.
            // An IP-free/legacy peer has the original wire format; old IP peers use TCP instead.
            if (!negotiated) original.call(packet, currentLevel);
            return;
        }
        if (!negotiated) return;
        // Packet input must not create a world or revive a disposed dimension.
        var addressed = ClientWorldLoader.getIsInitialized()
            ? ClientWorldLoader.getClientWorlds().stream()
                .filter(world -> world.dimension().equals(dimension)).findFirst().orElse(null)
            : null;
        if (addressed != null) {
            if (packet instanceof dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket) {
                SableSnapshotProtocol.recordUdpSnapshot();
            }
            original.call(packet, addressed);
        }
    }
}
