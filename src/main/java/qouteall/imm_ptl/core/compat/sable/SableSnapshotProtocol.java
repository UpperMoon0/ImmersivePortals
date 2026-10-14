package qouteall.imm_ptl.core.compat.sable;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Version-one trailer, sent only when the optional NeoForge channel is negotiated. */
public final class SableSnapshotProtocol {
    private static final int MAGIC = 0x49505331; // IPS1
    private SableSnapshotProtocol() {}
    private static final boolean DIAGNOSTICS = Boolean.getBoolean("ip.sable.e2e")
        || "true".equalsIgnoreCase(System.getenv("IP_SABLE_E2E"));
    private static long udpSnapshots, tcpSnapshots;
    public static void recordUdpSnapshot() { if (DIAGNOSTICS) udpSnapshots++; }
    public static void recordTcpSnapshot() { if (DIAGNOSTICS) tcpSnapshots++; }
    public static long udpSnapshots() { return udpSnapshots; }
    public static long tcpSnapshots() { return tcpSnapshots; }

    // No capability packet is sent. NeoForge's negotiated channel table is the evidence
    // that both peers understand this wire extension, including for integrated servers.
    public record Capability() implements CustomPacketPayload {
        public static final Type<Capability> TYPE = new Type<>(
            ResourceLocation.parse("imm_ptl:sable_snapshot_dimension_v1"));
        public static final StreamCodec<FriendlyByteBuf, Capability> CODEC = StreamCodec.unit(new Capability());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void write(FriendlyByteBuf buffer, @Nullable ResourceKey<Level> dimension) {
        if (dimension == null) return; // Legacy and redirected-TCP bytes remain unchanged.
        buffer.writeInt(MAGIC);
        buffer.writeResourceLocation(dimension.location());
    }

    public static @Nullable ResourceKey<Level> read(FriendlyByteBuf buffer) {
        if (!buffer.isReadable()) return null;
        if (buffer.readableBytes() < Integer.BYTES || buffer.readInt() != MAGIC) {
            throw new IllegalArgumentException("Invalid Sable snapshot dimension trailer");
        }
        var dimension = ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
        if (buffer.isReadable()) throw new IllegalArgumentException("Extra Sable snapshot dimension bytes");
        return dimension;
    }

    /** A server-side world comparison never establishes the client's processed world. */
    public static boolean useTaggedUdp(boolean peerSupportsV1, boolean sameServerDimension) {
        return peerSupportsV1 && sameServerDimension;
    }

    public static boolean declineLegacyPortalUdp(boolean peerSupportsV1, boolean peerHasPortalHandoff) {
        return !peerSupportsV1 && peerHasPortalHandoff;
    }
}
