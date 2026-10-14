package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.ryanhcode.sable.SableClient;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.network.client.SableClientNetworkEventLoop;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotDualPacket;
import dev.ryanhcode.sable.network.packets.ClientboundSableSnapshotInfoDualPacket;
import dev.ryanhcode.sable.network.udp.AddressedSableUDPPacket;
import dev.ryanhcode.sable.network.udp.SableUDPPacket;
import dev.ryanhcode.sable.network.udp.handler.SableUDPChannelHandlerClient;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable.SableDimensionTaggedSnapshot;
import qouteall.imm_ptl.core.compat.sable.SableSnapshotProtocol;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic real-codec/native-dispatch interleavings, isolated from the actual flight plots. */
final class SableSnapshotDimensionProbe {
    private static boolean complete;
    private static long udpBaseline, tcpBaseline;
    private SableSnapshotDimensionProbe() {}

    static void run(Minecraft minecraft) {
        if (complete) return;
        require(minecraft.getConnection().hasChannel(SableSnapshotProtocol.Capability.TYPE),
            "New/new fixture must negotiate snapshot-dimension v1");
        verifyQueueIsolation();
        ClientLevel sourceWorld = ClientWorldLoader.getWorld(Level.OVERWORLD);
        ClientLevel destinationWorld = ClientWorldLoader.getWorld(Level.NETHER);
        var source = (ClientSubLevelContainer) SubLevelContainer.getContainer(sourceWorld);
        var destination = (ClientSubLevelContainer) SubLevelContainer.getContainer(destinationWorld);
        int limit = Math.min(1 << source.getLogSideLength(), 1 << destination.getLogSideLength());
        int x = limit - 1, z = limit - 1;
        while (x >= 0 && (source.getSubLevel(x, z) != null || destination.getSubLevel(x, z) != null)) x--;
        require(x >= 0, "No common empty plot for isolated snapshot regression");
        ClientSubLevel sourceBody = null, destinationBody = null;
        Map<Field, Object> sourceClock = snapshotClock(source.getInterpolation());
        Map<Field, Object> destinationClock = snapshotClock(destination.getInterpolation());
        try {
            UUID bodyId = UUID.randomUUID();
            sourceBody = (ClientSubLevel) source.allocateSubLevel(bodyId, x, z, pose(10));
            destinationBody = (ClientSubLevel) destination.allocateSubLevel(bodyId, x, z, pose(210));
            long plot = ChunkPos.asLong(x, z);
            // Negative control: the unchanged native packet API reproduces the old dispatch
            // failure when execution-time current world is passed instead of provenance.
            var legacyWrongWorld = snapshot(plot, 1000000, 211);
            legacyWrongWorld.handleClient(sourceWorld);
            require(hasPose(sourceBody, 1000000, 211) && !hasPose(destinationBody, 1000000, 211),
                "Legacy dispatch control must demonstrate wrong-container contamination");
            sourceBody.getInterpolator().buffer.clear();

            // Authoritative dimension processing is delayed: destination UDP executes in source.
            dispatch(minecraft, tagged(snapshot(plot, 1000001, 212), Level.NETHER), sourceWorld);
            require(!hasPose(sourceBody, 1000001, 212) && hasPose(destinationBody, 1000001, 212),
                "Destination snapshot contaminated source before dimension switch");
            double sourceTick = clockTick(source.getInterpolation());
            dispatch(minecraft, tagged(new ClientboundSableSnapshotInfoDualPacket(50, 1000001, false), Level.NETHER), sourceWorld);
            require(clockTick(source.getInterpolation()) == sourceTick && clockTick(destination.getInterpolation()) == 1000001,
                "Destination snapshot-info contaminated source interpolation clock");

            // The client has switched: an older source datagram now executes in destination.
            dispatch(minecraft, tagged(snapshot(plot, 1000002, 12), Level.OVERWORLD), destinationWorld);
            require(hasPose(sourceBody, 1000002, 12) && !hasPose(destinationBody, 1000002, 12),
                "Delayed source snapshot contaminated destination after dimension switch");
            double destinationTick = clockTick(destination.getInterpolation());
            dispatch(minecraft, tagged(new ClientboundSableSnapshotInfoDualPacket(50, 1000002, false), Level.OVERWORLD), destinationWorld);
            require(clockTick(source.getInterpolation()) == 1000002 && clockTick(destination.getInterpolation()) == destinationTick,
                "Delayed source snapshot-info contaminated destination clock");

            ResourceKey<Level> absent = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("imm_ptl", "absent_snapshot_probe_" + UUID.randomUUID().toString().replace("-", "")));
            require(ClientWorldLoader.getOptionalWorld(absent) == null, "Missing-dimension precondition");
            int sourceSize = sourceBody.getInterpolator().buffer.size();
            int destinationSize = destinationBody.getInterpolator().buffer.size();
            dispatch(minecraft, tagged(snapshot(plot, 1000003, 999), absent), sourceWorld);
            dispatch(minecraft, tagged(new ClientboundSableSnapshotInfoDualPacket(50, 1000003, false), absent), destinationWorld);
            require(ClientWorldLoader.getOptionalWorld(absent) == null, "Packet created an untrusted world");
            require(sourceBody.getInterpolator().buffer.size() == sourceSize
                && destinationBody.getInterpolator().buffer.size() == destinationSize
                && clockTick(source.getInterpolation()) == 1000002
                && clockTick(destination.getInterpolation()) == destinationTick,
                "Missing-world datagram changed a loaded world");
            // An advertised dimension can be absent from the loaded-world registry after
            // removal or before first use. Hide only its registry entry synchronously; keep
            // its renderer/resources intact and restore the exact entry in finally.
            Map<ResourceKey<Level>, ClientLevel> worlds = loadedWorldRegistry();
            require(worlds.remove(Level.NETHER) == destinationWorld, "Loaded registry precondition");
            try {
                dispatch(minecraft, tagged(snapshot(plot, 1000003, 999), Level.NETHER), sourceWorld);
                dispatch(minecraft, tagged(new ClientboundSableSnapshotInfoDualPacket(50, 1000003, false), Level.NETHER), sourceWorld);
                require(!worlds.containsKey(Level.NETHER), "Packet recreated advertised unloaded world");
                require(destinationBody.getInterpolator().buffer.size() == destinationSize
                    && clockTick(destination.getInterpolation()) == destinationTick,
                    "Packet changed a removed world's body or clock");
            } finally { worlds.put(Level.NETHER, destinationWorld); }
            // Negotiated UDP rejects missing provenance, rather than guessing current world.
            dispatch(minecraft, snapshot(plot, 1000004, 999), sourceWorld);
            require(sourceBody.getInterpolator().buffer.size() == sourceSize, "Untagged negotiated UDP was accepted");
            legacyRoundTrips(minecraft, plot);
            complete = true;
            udpBaseline = SableSnapshotProtocol.udpSnapshots();
            tcpBaseline = SableSnapshotProtocol.tcpSnapshots();
            SableDimensionStackIntegrationMarkers.acknowledge("snapshot-dimensions");
        } finally {
            if (sourceBody != null) source.removeSubLevel(sourceBody, SubLevelRemovalReason.REMOVED);
            if (destinationBody != null) destination.removeSubLevel(destinationBody, SubLevelRemovalReason.REMOVED);
            restoreClock(source.getInterpolation(), sourceClock);
            restoreClock(destination.getInterpolation(), destinationClock);
        }
    }

    static void verifyActualTransport(Minecraft minecraft) {
        require(complete, "Snapshot interleaving regression did not finish");
        require(minecraft.getConnection().hasChannel(SableSnapshotProtocol.Capability.TYPE),
            "Snapshot capability disappeared before transport verification");
        long udp = SableSnapshotProtocol.udpSnapshots() - udpBaseline;
        long tcp = SableSnapshotProtocol.tcpSnapshots() - tcpBaseline;
        boolean expectsUdp = dev.ryanhcode.sable.SableClientConfig.ATTEMPT_UDP_NETWORKING.get();
        require(expectsUdp ? udp > 0 : udp == 0 && tcp > 0,
            "Actual snapshot transport differs from requested lane: expectedUdp=" + expectsUdp + " udp=" + udp + " tcp=" + tcp);
        SableDimensionStackIntegrationMarkers.snapshotTransport(
            "capability=v1 expectedUdp=" + expectsUdp + " actualNativeUdpSnapshots=" + udp
                + " actualRedirectedTcpSnapshots=" + tcp + " syntheticProbeExcluded=true");
    }

    private static void legacyRoundTrips(Minecraft minecraft, long plot) {
        for (SableUDPPacket packet : List.<SableUDPPacket>of(snapshot(plot, 17, 25),
            new ClientboundSableSnapshotInfoDualPacket(50, 17, false))) {
            var actual = new RegistryFriendlyByteBuf(Unpooled.buffer(), minecraft.level.registryAccess());
            var expected = new RegistryFriendlyByteBuf(Unpooled.buffer(), minecraft.level.registryAccess());
            try {
                packet.getType().write(actual, packet);
                if (packet instanceof ClientboundSableSnapshotDualPacket) {
                    expected.writeInt(17); expected.writeVarInt(1); expected.writeLong(plot);
                    dev.ryanhcode.sable.util.SableBufferUtils.write(expected, pose(25));
                    dev.ryanhcode.sable.util.SableBufferUtils.write(expected, new Vector3f());
                    dev.ryanhcode.sable.util.SableBufferUtils.write(expected, new Vector3f());
                } else { expected.writeInt(50); expected.writeInt(17); expected.writeBoolean(false); }
                require(io.netty.buffer.ByteBufUtil.equals(actual, expected), "Legacy TCP/UDP snapshot bytes changed");
                // Both transports use these same registered CODECs; no capability trailer is present.
                var decoded = packet.getType().create(actual);
                require(!actual.isReadable() && ((SableDimensionTaggedSnapshot) decoded).ip_getSnapshotDimension() == null,
                    "Legacy snapshot decode did not preserve absence of provenance");
            } finally { actual.release(); expected.release(); }
        }
    }

    private static Pose3d pose(double y) { var pose = new Pose3d(); pose.position().set(0, y, 0); return pose; }
    private static ClientboundSableSnapshotDualPacket snapshot(long plot, int tick, double y) {
        return new ClientboundSableSnapshotDualPacket(tick, List.of(
            new ClientboundSableSnapshotDualPacket.Entry(plot, pose(y), new Vector3f(), new Vector3f())));
    }
    private static SableUDPPacket tagged(SableUDPPacket packet, ResourceKey<Level> dimension) {
        ((SableDimensionTaggedSnapshot) packet).ip_setSnapshotDimension(dimension); return packet;
    }
    private static boolean hasPose(ClientSubLevel body, int tick, double y) {
        return body.getInterpolator().buffer.stream().anyMatch(s -> s.gameTick() == tick && Math.abs(s.pose().position().y() - y) < 1e-6);
    }

    private static void dispatch(Minecraft minecraft, SableUDPPacket packet, ClientLevel executionWorld) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), minecraft.level.registryAccess());
        SableClientNetworkEventLoop previousLoop = SableClient.NETWORK_EVENT_LOOP;
        SableClientNetworkEventLoop isolatedLoop = new SableClientNetworkEventLoop();
        ClientLevel previousWorld = minecraft.level;
        try {
            // This is the exact Sable UDP/local-loopback codec, including trailer consumption.
            packet.getType().write(buffer, packet);
            SableUDPPacket decoded = packet.getType().create(buffer);
            require(!buffer.isReadable(), "Snapshot codec left unread bytes");
            var handler = new SableUDPChannelHandlerClient(minecraft.getConnection().getConnection());
            var receive = SableUDPChannelHandlerClient.class.getDeclaredMethod("channelRead0",
                ChannelHandlerContext.class, AddressedSableUDPPacket.class);
            receive.setAccessible(true);
            SableClient.NETWORK_EVENT_LOOP = forwardingLoop(Thread.currentThread(), isolatedLoop, previousLoop);
            receive.invoke(handler, null, new AddressedSableUDPPacket(decoded, null));
            SableClient.NETWORK_EVENT_LOOP = previousLoop;
            minecraft.level = executionWorld;
            isolatedLoop.runAllTasks(); // Actual native deferred lambda, deliberately wrong current world.
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot execute native UDP dispatch regression", error);
        } finally {
            minecraft.level = previousWorld;
            SableClient.NETWORK_EVENT_LOOP = previousLoop;
            buffer.release();
        }
    }

    private static SableClientNetworkEventLoop forwardingLoop(Thread probeThread,
        SableClientNetworkEventLoop isolated, SableClientNetworkEventLoop live) {
        return new SableClientNetworkEventLoop() {
            @Override public void tell(Runnable task) {
                // A Netty thread retaining this forwarding instance after restoration still
                // sends to the live queue. Never drain or capture the real connection queue.
                (Thread.currentThread() == probeThread ? isolated : live).tell(task);
            }
        };
    }

    private static void verifyQueueIsolation() {
        var isolated = new SableClientNetworkEventLoop();
        var live = new SableClientNetworkEventLoop();
        var forwarding = forwardingLoop(Thread.currentThread(), isolated, live);
        int[] ran = new int[2];
        forwarding.tell(() -> ran[0]++);
        Thread background = new Thread(() -> forwarding.tell(() -> ran[1]++), "snapshot-probe-queue-check");
        background.start();
        try { background.join(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        isolated.runAllTasks();
        require(ran[0] == 1 && ran[1] == 0, "Background packet captured by probe queue");
        live.runAllTasks();
        require(ran[1] == 1, "Background packet lost by forwarding queue");
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceKey<Level>, ClientLevel> loadedWorldRegistry() {
        try {
            Field field = ClientWorldLoader.class.getDeclaredField("CLIENT_WORLD_MAP");
            field.setAccessible(true);
            return (Map<ResourceKey<Level>, ClientLevel>) field.get(null);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static Map<Field, Object> snapshotClock(Object clock) {
        Map<Field, Object> result = new LinkedHashMap<>();
        try {
            for (Field field : clock.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                field.setAccessible(true); result.put(field, field.get(clock));
            }
            return result;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static void restoreClock(Object clock, Map<Field, Object> saved) {
        try { for (var entry : saved.entrySet()) entry.getKey().set(clock, entry.getValue()); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static double clockTick(Object clock) {
        try { Field field = clock.getClass().getDeclaredField("mostRecentTick"); field.setAccessible(true); return field.getDouble(clock); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
