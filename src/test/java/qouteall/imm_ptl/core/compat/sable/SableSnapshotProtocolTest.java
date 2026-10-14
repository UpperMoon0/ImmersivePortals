package qouteall.imm_ptl.core.compat.sable;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SableSnapshotProtocolTest {
    @Test void capabilityAndRemoteWatcherTransportDecisionsAreExplicit() {
        assertTrue(SableSnapshotProtocol.useTaggedUdp(true, true), "New/new local viewers preserve native UDP");
        assertFalse(SableSnapshotProtocol.useTaggedUdp(true, false), "Remote watchers retain redirected TCP");
        assertFalse(SableSnapshotProtocol.useTaggedUdp(false, true), "New server/old client uses legacy-byte TCP");
        assertFalse(SableSnapshotProtocol.useTaggedUdp(false, false));
        assertTrue(SableSnapshotProtocol.declineLegacyPortalUdp(false, true), "New client/old IP server declines UDP auth");
        assertFalse(SableSnapshotProtocol.declineLegacyPortalUdp(true, true));
        assertFalse(SableSnapshotProtocol.declineLegacyPortalUdp(false, false), "IP-free servers are unchanged");
        // No static capability cache: reconnect decisions use the newly negotiated table.
        assertTrue(SableSnapshotProtocol.useTaggedUdp(true, true));
        assertFalse(SableSnapshotProtocol.useTaggedUdp(false, true));
    }

    @Test void versionOneRoundTripsBothDimensionsAndConsumesAllBytes() {
        for (var dimension : java.util.List.of(Level.OVERWORLD, Level.NETHER)) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                SableSnapshotProtocol.write(buffer, dimension);
                assertEquals(dimension, SableSnapshotProtocol.read(buffer));
                assertFalse(buffer.isReadable());
            } finally { buffer.release(); }
        }
    }

    @Test void absentTrailerPreservesTheLegacyWireExactly() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(123).writeBoolean(true);
            byte[] before = new byte[buffer.readableBytes()]; buffer.getBytes(0, before);
            SableSnapshotProtocol.write(buffer, null);
            byte[] after = new byte[buffer.readableBytes()]; buffer.getBytes(0, after);
            assertArrayEquals(before, after);
            buffer.skipBytes(before.length);
            assertNull(SableSnapshotProtocol.read(buffer));
        } finally { buffer.release(); }
    }

    @Test void malformedUnknownAndExtraTrailerDataFailClosed() {
        var good = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SableSnapshotProtocol.write(good, Level.NETHER);
            byte[] bytes = new byte[good.readableBytes()]; good.readBytes(bytes);
            for (int length = 1; length < bytes.length; length++) {
                var truncated = new FriendlyByteBuf(Unpooled.wrappedBuffer(java.util.Arrays.copyOf(bytes, length)));
                try { assertThrows(RuntimeException.class, () -> SableSnapshotProtocol.read(truncated)); }
                finally { truncated.release(); }
            }
            var unknown = new FriendlyByteBuf(Unpooled.buffer());
            try { unknown.writeInt(0x49505332).writeUtf("minecraft:the_nether");
                assertThrows(RuntimeException.class, () -> SableSnapshotProtocol.read(unknown));
            } finally { unknown.release(); }
            var invalid = new FriendlyByteBuf(Unpooled.buffer());
            try { invalid.writeInt(0x49505331).writeUtf("Invalid Dimension");
                assertThrows(RuntimeException.class, () -> SableSnapshotProtocol.read(invalid));
            } finally { invalid.release(); }
            var extra = new FriendlyByteBuf(Unpooled.buffer());
            try { extra.writeBytes(bytes).writeByte(0);
                assertThrows(RuntimeException.class, () -> SableSnapshotProtocol.read(extra));
            } finally { extra.release(); }
        } finally { good.release(); }
    }
}
