package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import qouteall.imm_ptl.core.portal.Portal;
import java.util.ArrayList;
import java.util.List;

/** A real cross-dimensional rendering scene, independent of Sable. Never shipped. */
@EventBusSubscriber(modid = qouteall.imm_ptl.core.platform_specific.IPModEntry.MODID)
public final class PortalSmokeServer {
    private static final List<Double> timings = new ArrayList<>();
    private static long started;
    private static boolean ready;

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!PortalSmokeSupport.enabled() || !(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            boolean expectedSable = Boolean.parseBoolean(System.getenv().getOrDefault("IP_SMOKE_SABLE", "true"));
            if (ModList.get().isLoaded("sable") != expectedSable) throw new IllegalStateException("Wrong Sable runtime");
            ServerLevel source = player.server.getLevel(Level.OVERWORLD);
            ServerLevel target = player.server.getLevel(Level.NETHER);
            // The source wall is red. Only a working portal can reveal the green destination wall.
            // Clear the whole viewing volume, including terrain generated in the Nether.
            for (int x = -8; x <= 8; x++) for (int y = 76; y <= 88; y++) for (int z = -5; z <= 5; z++) {
                BlockPos pos = new BlockPos(x, y, z);
                source.setBlockAndUpdate(pos, z == -4 ? Blocks.RED_CONCRETE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                // This red wall lies between the transformed camera and the destination
                // portal plane. Correct front clipping must remove it from the portal view.
                target.setBlockAndUpdate(pos, z == -4 ? Blocks.LIME_CONCRETE.defaultBlockState()
                    : z == 1 ? Blocks.RED_CONCRETE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            Portal portal = Portal.ENTITY_TYPE.create(source);
            portal.setOriginPos(new Vec3(0, 82, 0));
            portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 6, 6);
            portal.setDestinationDimension(Level.NETHER);
            portal.setDestination(new Vec3(0, 82, 0));
            source.addFreshEntity(portal);
            player.setGameMode(GameType.SPECTATOR);
            player.teleportTo(source, 0, 80.38, 4, 180, 0);
            ready = true;
            PortalSmokeSupport.write("scene-ready.txt", "red source / green destination portal scene\n");
        } catch (Throwable e) {
            PortalSmokeSupport.write("server-fail.txt", e.toString());
        }
    }

    @SubscribeEvent
    public static void beforeTick(ServerTickEvent.Pre event) {
        if (PortalSmokeSupport.enabled()) started = System.nanoTime();
    }

    @SubscribeEvent
    public static void afterTick(ServerTickEvent.Post event) {
        if (!PortalSmokeSupport.enabled() || !ready || !PortalSmokeSupport.exists("visual-pass.txt")) return;
        if (timings.size() >= PortalSmokeSupport.samples()) return;
        timings.add((System.nanoTime() - started) / 1_000_000.0);
        if (timings.size() == PortalSmokeSupport.samples()) {
            PortalSmokeSupport.metrics("server", timings);
            PortalSmokeSupport.write("server-pass.txt", "Live portal server tick measurements complete\n");
        }
    }
}
