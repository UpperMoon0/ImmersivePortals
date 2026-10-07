package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import qouteall.imm_ptl.core.portal.Portal;

@EventBusSubscriber(modid = qouteall.imm_ptl.core.platform_specific.IPModEntry.MODID)
public final class PortalShadowSmokeServer {
    private static ServerPlayer player;
    private static String request = "";
    private static long tickStart;
    private static final List<Double> timings = new ArrayList<>();

    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!PortalShadowTestControl.enabled() || !(event.getEntity() instanceof ServerPlayer joined)) return;
        player = joined;
        player.setGameMode(GameType.SPECTATOR);
    }
    @SubscribeEvent public static void beforeTick(ServerTickEvent.Pre event) {
        if (PortalShadowTestControl.enabled()) tickStart = System.nanoTime();
    }
    @SubscribeEvent public static void afterTick(ServerTickEvent.Post event) {
        if (!PortalShadowTestControl.enabled() || player == null) return;
        try {
            String next = PortalSmokeSupport.read("shadow-request.txt");
            if (!next.isEmpty() && !next.equals(request)) setup(next);
            if (PortalSmokeSupport.exists("visual-pass.txt") && timings.size() < PortalSmokeSupport.samples()) {
                timings.add((System.nanoTime() - tickStart) / 1_000_000.0);
                if (timings.size() == PortalSmokeSupport.samples()) {
                    PortalSmokeSupport.metrics("server", timings);
                    PortalSmokeSupport.write("server-pass.txt", "Shadow receiver/depth acceptance server complete");
                }
            }
        } catch (Throwable error) {
            PortalSmokeSupport.write("server-fail.txt", error.toString());
        }
    }
    private static void setup(String next) {
        ServerLevel source = player.server.getLevel(Level.OVERWORLD);
        ServerLevel target = player.server.getLevel(Level.NETHER);
        for (ServerLevel level : List.of(source, target)) {
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, player.server);
            level.setDayTime(6000);
            level.getEntitiesOfClass(Entity.class, new AABB(-16, 70, -16, 16, 96, 16),
                entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
            for (int x = -8; x <= 8; x++) for (int y = 76; y <= 88; y++) for (int z = -5; z <= 8; z++) {
                level.setBlockAndUpdate(new BlockPos(x, y, z), z == -4
                    ? (level == source ? Blocks.RED_CONCRETE : Blocks.LIME_CONCRETE).defaultBlockState()
                    : Blocks.AIR.defaultBlockState());
            }
        }
        if (next.endsWith(":caster")) {
            // Entire caster volume is z=1..2, excluded from the portal's z<0 destination.
            for (int x = -2; x <= 1; x++) for (int y = 80; y <= 83; y++) {
                target.setBlockAndUpdate(new BlockPos(x, y, 1), Blocks.RED_CONCRETE.defaultBlockState());
            }
        }
        player.teleportTo(source, 0, 80.38, 4, 180, 0);
        Portal portal = Portal.ENTITY_TYPE.create(source);
        portal.setOriginPos(new Vec3(0, 82, 0));
        portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 6, 6);
        portal.setDestinationDimension(Level.NETHER);
        portal.setDestination(new Vec3(0, 82, 0));
        source.addFreshEntity(portal);
        request = next;
        PortalSmokeSupport.write("shadow-ready.txt", request);
    }
}
