package qouteall.imm_ptl.core.compat.mixin.sable;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import qouteall.imm_ptl.core.compat.sable.SableInterface;
import qouteall.imm_ptl.core.mixin.common.chunk_sync.MixinChunkHolder;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The live ship suite additionally verifies actual post-full-sync Create RPM packets. */
class SablePlotChunkBroadcastContractTest {
    @Test
    void absentSableLeavesOrdinaryChunkRecipientSelectionUnchanged() {
        assertNull(new SableInterface.Invoker().getPlotChunkBroadcastPlayers(null, new ChunkPos(0, 0)));
    }

    @Test
    void plotRecipientsIncludingEmptyListsBypassWorldCoordinateTracking() throws Exception {
        SableInterface.Invoker previous = SableInterface.invoker;
        try {
            // A null player is a recipient identity placeholder; this test never sends packets.
            for (List<ServerPlayer> expected : List.of(List.<ServerPlayer>of(), Collections.<ServerPlayer>singletonList(null))) {
                ChunkPos plot = new ChunkPos(1_000_000, 1_000_000);
                SableInterface.invoker = new SableInterface.Invoker() {
                    @Override
                    public List<ServerPlayer> getPlotChunkBroadcastPlayers(ServerLevel level, ChunkPos chunkPos) {
                        assertSame(plot, chunkPos);
                        return expected;
                    }
                };
                MixinChunkHolder holder = new MixinChunkHolder() {
                    @Override public int getTicketLevel() { return 0; }
                };
                Method select = MixinChunkHolder.class.getDeclaredMethod("redirectGetPlayers",
                    ChunkHolder.PlayerProvider.class, ChunkPos.class, boolean.class);
                select.setAccessible(true);
                for (boolean boundaryOnly : new boolean[] {false, true}) {
                    assertSame(expected, select.invoke(holder, null, plot, boundaryOnly),
                        "Plot tracking owns both ordinary block/BE and boundary-light recipients, even when empty");
                }
            }
        } finally {
            SableInterface.invoker = previous;
        }
    }

    @Test
    void pinnedSablePlotRecipientApiFindsRemoteObserversThroughGlobalPlayerList() throws Exception {
        ClassNode container = readClass("dev/ryanhcode/sable/api/sublevel/SubLevelContainer.class");
        MethodNode recipients = method(container, "getPlayersTracking",
            "(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/List;");
        assertInvokes(recipients, "dev/ryanhcode/sable/sublevel/ServerSubLevel", "getTrackingPlayers");
        assertInvokes(recipients, "net/minecraft/server/MinecraftServer", "getPlayerList");
        assertInvokes(recipients, "net/minecraft/server/players/PlayerList", "getPlayer");

        ClassNode optional = readClass("qouteall/imm_ptl/core/compat/sable/SableInterface$OnSablePresent.class");
        MethodNode hook = method(optional, "getPlotChunkBroadcastPlayers",
            "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;)Ljava/util/List;");
        assertInvokes(hook, "dev/ryanhcode/sable/api/sublevel/SubLevelContainer", "inBounds");
        assertInvokes(hook, "dev/ryanhcode/sable/api/sublevel/SubLevelContainer", "getPlayersTracking");
    }

    @Test
    void ordinaryChunkFallbackAndDimensionEnvelopeRemainInBroadcastPath() throws Exception {
        ClassNode holder = readClass("qouteall/imm_ptl/core/mixin/common/chunk_sync/MixinChunkHolder.class");
        MethodNode select = method(holder, "redirectGetPlayers",
            "(Lnet/minecraft/server/level/ChunkHolder$PlayerProvider;Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;");
        assertInvokes(select, "qouteall/imm_ptl/core/compat/sable/SableInterface$Invoker", "getPlotChunkBroadcastPlayers");
        assertInvokes(select, "qouteall/imm_ptl/core/chunk_loading/ImmPtlChunkTracking", "getPlayersViewingChunk");
        assertInvokes(method(holder, "modifyPacket", "(Lnet/minecraft/network/protocol/Packet;)Lnet/minecraft/network/protocol/Packet;"),
            "qouteall/imm_ptl/core/network/PacketRedirection", "createRedirectedMessage");
    }

    private static ClassNode readClass(String path) throws Exception {
        try (InputStream input = SablePlotChunkBroadcastContractTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, path);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst().orElseThrow(() -> new AssertionError(node.name + "." + name + descriptor));
    }

    private static void assertInvokes(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return;
        }
        fail(method.name + " must invoke " + owner + "." + name);
    }
}
