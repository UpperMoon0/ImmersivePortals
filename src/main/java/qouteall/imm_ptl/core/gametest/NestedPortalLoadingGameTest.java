package qouteall.imm_ptl.core.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.chunk_loading.ChunkVisibility;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.chunk_loading.PerformanceLevel;
import qouteall.imm_ptl.core.chunk_loading.ServerPerformanceMonitor;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.UUID;

/** Enumerates real portal entities through the production loader entry point. */
@GameTestHolder("sable")
public final class NestedPortalLoadingGameTest {
    private NestedPortalLoadingGameTest() {}

    @PrefixGameTestTemplate(false)
    @GameTest(template = "physicstest.gravity", timeoutTicks = 20)
    public static void nestedDestinationSurvivesEveryServerPerformanceLevel(GameTestHelper helper) throws ReflectiveOperationException {
        var world = helper.getLevel();
        var player = FakePlayerFactory.get(world, new GameProfile(UUID.randomUUID(), "NestedLoaderTest"));
        Vec3 source = Vec3.atCenterOf(helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)));
        // Separate the two portal searches vertically in the same already-loaded
        // chunk column. No extra chunk tickets, players, or worlds are loaded here.
        Vec3 intermediate = source.add(0, 64, 0);
        Vec3 destination = new Vec3(160_000, 82, 160_000);
        Portal outer = createPortal(world, world.dimension(), source, intermediate, player.getUUID());
        Portal inner = createPortal(world, Level.END, intermediate, destination, player.getUUID());
        player.setPos(source.add(0, 0, 4));
        var playerInfo = ImmPtlChunkTracking.getPlayerInfo(player);
        playerInfo.performanceLevel = PerformanceLevel.bad;

        var performanceField = ServerPerformanceMonitor.class.getDeclaredField("level");
        performanceField.setAccessible(true);
        Object previousPerformance = performanceField.get(null);
        try {
            helper.assertTrue(world.addFreshEntity(outer), "Outer portal must enter the real entity lookup");
            helper.assertTrue(world.addFreshEntity(inner), "Inner portal must enter the real entity lookup");
            for (var performance : PerformanceLevel.values()) {
                performanceField.set(null, performance);
                var loaders = new ArrayList<ChunkLoader>();
                ChunkVisibility.foreachBaseChunkLoaders(player, loaders::add);
                var destinationLoaders = loaders.stream().filter(loader -> loader.dimension() == Level.END
                    && loader.x() == 10_000 && loader.z() == 10_000).toList();
                helper.assertTrue(destinationLoaders.size() == 1,
                    performance + " must enumerate exactly one second-hop destination: " + loaders);
                ChunkLoader loader = destinationLoaders.getFirst();
                int goodVisibleRadius = Math.min(Math.max(1, McHelper.getPlayerLoadDistance(player) / 4),
                    Math.min(2, qouteall.imm_ptl.core.IPGlobal.indirectLoadingRadiusCap));
                int expectedRadius = (performance == PerformanceLevel.good ? goodVisibleRadius
                    : Math.min(goodVisibleRadius, 1)) + 1;
                helper.assertTrue(loader.radius() == expectedRadius,
                    performance + " must preserve the cap and mesh-data ring: " + loader);
                helper.assertTrue(loader.getChunkNum() == (expectedRadius * 2 + 1) * (expectedRadius * 2 + 1),
                    performance + " loader chunk count must remain bounded");
            }
        }
        finally {
            performanceField.set(null, previousPerformance);
            outer.discard();
            inner.discard();
            ImmPtlChunkTracking.forceRemovePlayer(player);
        }
        helper.succeed();
    }

    private static Portal createPortal(net.minecraft.server.level.ServerLevel world,
        net.minecraft.resources.ResourceKey<Level> destinationDimension, Vec3 from, Vec3 to, UUID observer) {
        Portal portal = Portal.ENTITY_TYPE.create(world);
        if (portal == null) throw new IllegalStateException("Portal entity type is unavailable");
        portal.specificPlayerId = observer;
        portal.setOriginPos(from);
        portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 6, 6);
        portal.setDestinationDimension(destinationDimension);
        portal.setDestination(to);
        return portal;
    }
}
