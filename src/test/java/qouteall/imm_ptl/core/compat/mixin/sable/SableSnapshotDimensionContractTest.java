package qouteall.imm_ptl.core.compat.mixin.sable;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** Pin the native dispatch and codec paths actually exercised by the graphical regression. */
class SableSnapshotDimensionContractTest {
    private static final String BASE = "dev/ryanhcode/sable/";
    @Test void bothNativeDispatchersRemainInterceptable() throws Exception {
        for (String path : new String[] {"network/udp/handler/SableUDPChannelHandlerClient", "network/udp/SableUDPServer"}) {
            ClassNode type = read(BASE + path);
            long calls = type.methods.stream().flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .filter(i -> i instanceof MethodInsnNode c && c.owner.equals(BASE + "network/udp/SableUDPPacket")
                    && c.name.equals("handleClient") && c.desc.equals("(Lnet/minecraft/world/level/Level;)V")).count();
            assertEquals(1, calls, "Both UDP network and serialized local-loopback calls need dimension dispatch");
        }
    }

    @Test void bothSnapshotFormatsShareThePinnedTcpAndUdpCodecs() throws Exception {
        for (String name : new String[] {"ClientboundSableSnapshotDualPacket", "ClientboundSableSnapshotInfoDualPacket"}) {
            ClassNode type = read(BASE + "network/packets/" + name);
            assertTrue(type.interfaces.contains(BASE + "network/udp/SableUDPPacket"));
            assertTrue(type.interfaces.contains(BASE + "network/tcp/SableTCPPacket"));
            assertNotNull(method(type, "<init>", "(Lio/netty/buffer/ByteBuf;)V"));
            assertNotNull(method(type, "encode", "(Lio/netty/buffer/ByteBuf;)V"));
            assertNotNull(method(type, "handle", "(Lfoundry/veil/api/network/handler/PacketContext;)V"));
        }
        ClassNode tracking = read(BASE + "sublevel/system/SubLevelTrackingSystem");
        MethodNode send = tracking.methods.stream().filter(m -> m.name.equals("sendMovementUpdates")).findFirst().orElseThrow();
        assertEquals(2, count(send, "sendUDPPacket"), "Both snapshot and snapshot-info sends need negotiation/tagging");
    }

    @Test void productionSenderChecksNegotiationAndTagsBeforeNativeSend() throws Exception {
        ClassNode sender = read("qouteall/imm_ptl/core/compat/mixin/sable/MixinSubLevelTrackingSystem_SablePortalCompat");
        MethodNode method = sender.methods.stream().filter(m -> m.name.equals("ip_routeRemoteSableUdpThroughRedirectedTcp")).findFirst().orElseThrow();
        assertEquals(1, count(method, "hasChannel"));
        assertEquals(1, count(method, "useTaggedUdp"));
        assertEquals(1, count(method, "withForceRedirect"), "Legacy/remote branch must retain dimension-enveloped TCP");
        assertEquals(1, count(method, "ip_setSnapshotDimension"));
        assertTrue(index(method, "hasChannel") < index(method, "useTaggedUdp"));
        assertTrue(index(method, "ip_setSnapshotDimension") < index(method, "call"));
    }

    @Test void capabilityIsOptionalAndAdvertisedInBothDirections() throws Exception {
        MethodNode register = read("de/nick1st/imm_ptl/networking/Payloads").methods.stream()
            .filter(m -> m.name.equals("register")).findFirst().orElseThrow();
        assertTrue(index(register, "optional") >= 0 && index(register, "optional") < index(register, "playBidirectional"));
        ClassNode activation = read("qouteall/imm_ptl/core/compat/mixin/sable/MixinSableUdpActivation_DimensionTag");
        MethodNode gate = activation.methods.stream().filter(m -> m.name.startsWith("ip_useOrderedFallback")).findFirst().orElseThrow();
        assertEquals(2, count(gate, "hasChannel"), "Current connection must supply both capability and IP-presence evidence");
        assertEquals(1, count(gate, "declineLegacyPortalUdp"));
        assertEquals(1, count(gate, "cancel"));
    }

    @Test void nativeProbeInitializesRenderLifecycleBeforeDispatchAndReceipts() throws Exception {
        MethodNode run = read("qouteall/imm_ptl/core/gametest/sablee2e/SableSnapshotDimensionProbe").methods.stream()
            .filter(m -> m.name.equals("run")).findFirst().orElseThrow();
        assertEquals(2, count(run, "updateRenderData"), "Both temporary plots need native renderer initialization");
        int firstSnapshot = index(run, "snapshot");
        assertTrue(firstSnapshot >= 0);
        for (int i = 0; i < run.instructions.size(); i++) {
            if (run.instructions.get(i) instanceof MethodInsnNode call && call.name.equals("updateRenderData")) {
                assertTrue(i < firstSnapshot, "Each renderer must initialize before any probe packet");
            }
        }
        assertTrue(index(run, "removeSubLevel") < index(run, "acknowledge"), "Receipt follows successful cleanup");
    }

    @Test void historyOracleRunsAtGraftBeforeNativeSamplingAndRequiresReceipts() throws Exception {
        MethodNode graft = read("qouteall/imm_ptl/core/compat/mixin/sable/MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat")
            .methods.stream().filter(m -> m.name.equals("ip_graftSourceInterpolation")).findFirst().orElseThrow();
        assertTrue(index(graft, "verify") > index(graft, "sort"));
        assertTrue(index(graft, "verify") < index(graft, "setInitialPosesFrom"));
        ClassNode client = read("qouteall/imm_ptl/core/gametest/sablee2e/SableDimensionStackDedicatedClientTest");
        MethodNode tick = client.methods.stream().filter(m -> m.name.equals("onClientTick")).findFirst().orElseThrow();
        assertTrue(index(tick, "setObserver") >= 0);
        assertTrue(index(tick, "setObserver") < index(tick, "connectWhenReady"),
            "Install the graft oracle before starting the connection");
        boolean oracleInstalled = false;
        for (var instruction : tick.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode lambda) {
                for (Object argument : lambda.bsmArgs) {
                    if (argument instanceof org.objectweb.asm.Handle handle
                        && handle.getOwner().equals("qouteall/imm_ptl/core/gametest/sablee2e/SableInterpolationHistoryProbe")
                        && handle.getName().equals("verify")) oracleInstalled = true;
                }
            }
        }
        assertTrue(oracleInstalled, "The observer must retain the original preservation oracle");
        for (String name : new String[] {"waitForReturnRide", "waitForGravityRecross"}) {
            MethodNode method = client.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            assertEquals(1, count(method, "requireVerified"), "Every later crossing requires a checked graft");
        }
        MethodNode probe = read("qouteall/imm_ptl/core/gametest/sablee2e/SableSnapshotDimensionProbe").methods.stream()
            .filter(m -> m.name.equals("run")).findFirst().orElseThrow();
        assertEquals(1, count(probe, "verifyNativePruning"));
        assertEquals(1, count(probe, "verifyReceiptIsolation"));
    }

    @Test void lifecycleProbeClosesNetworkBeforeSyntheticCleanupOrWorldTeardown() throws Exception {
        MethodNode cleanup = read("qouteall/imm_ptl/core/gametest/sablee2e/SableRiderLifecycleProbe").methods.stream()
            .filter(m -> m.name.equals("cleanupAndDisconnect")).findFirst().orElseThrow();
        int close = -1, clear = -1;
        for (int i = 0; i < cleanup.instructions.size(); i++) {
            if (!(cleanup.instructions.get(i) instanceof MethodInsnNode call) || !call.name.equals("disconnect")) continue;
            if (call.owner.equals("net/minecraft/network/Connection")) close = i;
            if (call.owner.equals("net/minecraft/client/Minecraft")) clear = i;
        }
        assertTrue(close >= 0 && close < index(cleanup, "post") && close < clear);
        assertEquals(1, count(cleanup, "isConnected"));
    }

    private static int count(MethodNode method, String name) {
        int result = 0; for (var i : method.instructions) if (i instanceof MethodInsnNode c && c.name.equals(name)) result++; return result;
    }
    private static int index(MethodNode method, String name) {
        for (int i = 0; i < method.instructions.size(); i++) if (method.instructions.get(i) instanceof MethodInsnNode c && c.name.equals(name)) return i;
        return -1;
    }
    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElse(null);
    }
    private static ClassNode read(String name) throws Exception {
        try (var stream = SableSnapshotDimensionContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(stream, "Missing class " + name);
            var type = new ClassNode(); new ClassReader(stream).accept(type, 0); return type;
        }
    }
}
