package qouteall.imm_ptl.core.compat.mixin.sable;

import dev.ryanhcode.sable.ActiveSableCompanion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import qouteall.imm_ptl.core.compat.sable.SableInterface;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SablePortalCompatContractTest {
    private static final String TARGET =
        "dev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem.class";

    @Test
    void sableTrackingBytecodeMatchesMixinContract() throws Exception {
        ClassNode target = readClass(TARGET);

        assertMethod(target, "shouldLoad", "(Lnet/minecraft/world/entity/player/Player;Lorg/joml/Vector3dc;)Z");
        assertMethod(target, "collectPlayers", "(Lorg/joml/Vector3d;Ljava/util/Collection;)V");

        MethodNode tick = findMethod(target, "tick", "(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V");
        assertNotNull(tick, "Sable tracking tick signature changed");
        assertTrue(tick.instructions.iterator().hasNext(), "Sable tracking tick has no bytecode");
        int matchingPlayerLookups = 0;
        List<String> playerLookups = new ArrayList<>();
        for (var instruction : tick.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("getPlayerByUUID")) {
                playerLookups.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/server/level/ServerLevel")
                    && call.desc.equals("(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;")) {
                    matchingPlayerLookups++;
                }
            }
        }
        assertTrue(matchingPlayerLookups == 2,
            "Expected two player lookups targeted by the portal watcher redirect, found: " + playerLookups);
    }

    @Test
    void compatMixinIsPackagedAndEnabled() throws Exception {
        assertNotNull(getClass().getClassLoader().getResource(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSubLevelTrackingSystem_SablePortalCompat.class"));
        assertNotNull(getClass().getClassLoader().getResource(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat.class"));
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("imm_ptl_compat.mixins.json")) {
            assertNotNull(stream, "Compatibility mixin config is missing");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("sable.MixinSubLevelTrackingSystem_SablePortalCompat"));
            assertTrue(json.contains("sable.MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat"));
        }
    }

    @Test
    void ordinaryEntitiesKeepTheirStoredTrackingPosition() {
        Vec3 position = new Vec3(12.5, 64.0, -31.25);
        SableInterface.Invoker invoker = new SableInterface.Invoker();

        assertSame(position, invoker.getEntityTrackingPosition(null, position));
        assertTrue(invoker.getEntityTrackingChunk(null, position).equals(new ChunkPos(0, -2)));
    }

    @Test
    void sableProjectionApiMatchesUnifiedTrackerContract() throws Exception {
        assertNotNull(ActiveSableCompanion.class.getMethod(
            "projectOutOfSubLevel", Level.class, Vec3.class
        ));
    }

    @Test
    void immersivePortalsIsTheOnlyRuntimePairingOwner() throws Exception {
        ClassNode tracker = readClass(
            "qouteall/imm_ptl/core/mixin/common/entity_sync/MixinTrackedEntity.class"
        );

        MethodNode cancelOne = findMethod(
            tracker,
            "ip_cancelVanillaUpdatePlayer",
            "(Lnet/minecraft/server/level/ServerPlayer;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"
        );
        MethodNode cancelMany = findMethod(
            tracker,
            "ip_cancelVanillaUpdatePlayers",
            "(Ljava/util/List;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"
        );
        assertNotNull(cancelOne, "Vanilla/Sable single-player pairing must be cancelled at runtime");
        assertNotNull(cancelMany, "Vanilla/Sable bulk pairing must be cancelled at runtime");
        assertInvokes(cancelOne, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel");
        assertInvokes(cancelMany, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel");
    }

    @Test
    void portalWatchRecordsUseTheSableLogicalPosition() throws Exception {
        ClassNode tracker = readClass(
            "qouteall/imm_ptl/core/mixin/common/entity_sync/MixinTrackedEntity.class"
        );
        MethodNode update = findMethod(tracker, "ip_updateEntityTrackingStatus", "()V");
        assertNotNull(update);

        assertInvokes(
            update,
            "qouteall/imm_ptl/core/compat/sable/SableInterface$Invoker",
            "getEntityTrackingChunk"
        );
        assertInvokes(
            update,
            "qouteall/imm_ptl/core/chunk_loading/ImmPtlChunkTracking",
            "getWatchRecordForChunk"
        );
        assertFalse(invokes(update, "net/minecraft/world/entity/Entity", "chunkPosition"),
            "Raw Sable plot chunks must not select portal watch records");
    }

    @Test
    void movementPacketsUseTheSameLogicalTrackingChunk() throws Exception {
        ClassNode entitySync = readClass(
            "qouteall/imm_ptl/core/chunk_loading/EntitySync.class"
        );
        MethodNode tick = findMethod(
            entitySync,
            "tick",
            "(Lnet/minecraft/server/MinecraftServer;)V"
        );
        assertNotNull(tick);

        assertClassInvokes(
            entitySync,
            "qouteall/imm_ptl/core/compat/sable/SableInterface$Invoker",
            "getEntityTrackingChunk"
        );
        assertFalse(classInvokes(entitySync, "net/minecraft/world/entity/Entity", "chunkPosition"),
            "Raw Sable plot chunks must not gate per-tick movement packets");
    }

    private static ClassNode readClass(String resource) throws Exception {
        try (InputStream stream = SablePortalCompatContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "Sable is missing from the test runtime");
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static void assertMethod(ClassNode node, String name, String descriptor) {
        assertNotNull(findMethod(node, name, descriptor), name + " descriptor changed: expected " + descriptor);
    }

    private static void assertInvokes(MethodNode method, String owner, String name) {
        assertTrue(invokes(method, owner, name),
            method.name + " must invoke " + owner + "." + name);
    }

    private static void assertClassInvokes(ClassNode node, String owner, String name) {
        assertTrue(classInvokes(node, owner, name),
            node.name + " must invoke " + owner + "." + name);
    }

    private static boolean classInvokes(ClassNode node, String owner, String name) {
        return node.methods.stream().anyMatch(method -> invokes(method, owner, name));
    }

    private static boolean invokes(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                && call.owner.equals(owner)
                && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
            .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst()
            .orElse(null);
    }
}
