package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import qouteall.imm_ptl.core.network.PacketRedirection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;

/** Dedicated, deterministic scenes. This entire package is excluded from release jars. */
@EventBusSubscriber(modid = qouteall.imm_ptl.core.platform_specific.IPModEntry.MODID)
public final class PortalSmokeServer {
    private static final List<Double> timings = new ArrayList<>();
    private static final List<Entity> fixtures = new ArrayList<>();
    private static long started;
    private static ServerPlayer player;
    private static String request = "";
    private static String scene = "";
    private static int particleZ;

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!PortalSmokeSupport.enabled() || !(event.getEntity() instanceof ServerPlayer joined)) return;
        try {
            boolean expectedSable = Boolean.parseBoolean(System.getenv().getOrDefault("IP_SMOKE_SABLE", "true"));
            if (ModList.get().isLoaded("sable") != expectedSable) throw new IllegalStateException("Wrong Sable runtime");
            player = joined;
            player.setGameMode(GameType.SPECTATOR);
        } catch (Throwable e) {
            PortalSmokeSupport.write("server-fail.txt", e.toString());
        }
    }

    private static void setup(String token) throws ReflectiveOperationException {
        String previousScene = scene;
        scene = token.substring(token.indexOf(':') + 1);
        fixtures.forEach(Entity::discard);
        fixtures.clear();
        ServerLevel source = player.server.getLevel(Level.OVERWORLD);
        ServerLevel target = player.server.getLevel(Level.NETHER);
        ServerLevel end = player.server.getLevel(Level.END);
        // Mutate only this freshly generated disposable world's bounded fixture volume.
        for (ServerLevel level : List.of(source, target, end)) {
            level.getEntitiesOfClass(Entity.class, new AABB(-16, 70, -16, 48, 96, 16),
                entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
            level.setDayTime(6000);
            for (int x = -8; x <= 8; x++) for (int y = 76; y <= 88; y++) for (int z = -5; z <= 8; z++) {
                BlockState state = z == -4 ? (level == source ? Blocks.RED_CONCRETE : Blocks.LIME_CONCRETE).defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
                level.setBlockAndUpdate(new BlockPos(x, y, z), state);
            }
        }
        if (scene.equals("create-nested") || previousScene.equals("create-nested")) {
            for (int x = 24; x <= 40; x++) for (int y = 76; y <= 88; y++) for (int z = -5; z <= 8; z++) {
                target.setBlockAndUpdate(new BlockPos(x, y, z), (scene.equals("create-nested") && z == -4)
                    ? Blocks.LIME_CONCRETE.defaultBlockState() : (scene.equals("create-nested") && z == 1)
                    ? Blocks.RED_CONCRETE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
        player.teleportTo(source, 0, 80.38, 4, 180, 0);
        boolean visible = scene.endsWith("-visible");
        int z = visible ? -1 : 1;
        if (scene.equals("mirror")) {
            wall(source, -1, Blocks.RED_CONCRETE.defaultBlockState());
            wall(source, 6, Blocks.LIME_CONCRETE.defaultBlockState());
            Mirror mirror = Mirror.ENTITY_TYPE.create(source);
            configure(mirror, Level.OVERWORLD, new Vec3(0, 82, 0), new Vec3(0, 82, 0));
            add(source, mirror);
        } else {
            Portal portal = Portal.ENTITY_TYPE.create(source);
            configure(portal, Level.NETHER, new Vec3(0, 82, 0), new Vec3(0, 82, 0));
            add(source, portal);
            if (scene.equals("nested") || scene.equals("create-nested")) {
                wall(target, -4, Blocks.RED_CONCRETE.defaultBlockState());
                wall(end, 1, Blocks.RED_CONCRETE.defaultBlockState());
                Portal nested = Portal.ENTITY_TYPE.create(target);
                configure(nested, scene.equals("create-nested") ? Level.NETHER : Level.END,
                    new Vec3(0, 82, -1), new Vec3(scene.equals("create-nested") ? 32 : 0, 82, 0));
                add(target, nested);
                if (scene.equals("create-nested")) {
                    // Re-enter the same Nether LevelRenderer to test Flywheel's per-level context restoration.
                    var setup = Class.forName("qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeCreateScene")
                        .getMethod("setup", ServerLevel.class, int.class, int.class, int.class);
                    setup.invoke(null, target, 28, 82, -3);
                    setup.invoke(null, source, 0, 82, -3);
                }
            } else if (scene.startsWith("cutout-")) {
                wall(target, z, Blocks.OAK_LEAVES.defaultBlockState());
            } else if (scene.startsWith("translucent-")) {
                wall(target, z, Blocks.RED_STAINED_GLASS.defaultBlockState());
            } else if (scene.startsWith("block-entity-")) {
                wall(target, z, Blocks.RED_SHULKER_BOX.defaultBlockState());
            } else if (scene.startsWith("entity-")) {
                for (int x = -3; x <= 3; x++) for (int y = 80; y <= 84; y++) {
                    Mob mob = EntityType.PIG.create(target);
                    mob.setPos(x, y, z);
                    mob.setNoAi(true);
                    mob.setNoGravity(true);
                    mob.setInvulnerable(true);
                    add(target, mob);
                }
            } else if (scene.startsWith("particle-")) {
                particleZ = z;
            } else if (scene.startsWith("create-")) {
                // The optional helper is kept separate from this no-Sable discovery boundary.
                Class<?> helper = Class.forName("qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeCreateScene");
                boolean crumbling = scene.startsWith("create-crumbling-");
                int targetX = crumbling ? 0 : -4;
                int targetZ = scene.endsWith("-clipped") ? 1 : -3;
                helper.getMethod("setup", ServerLevel.class, int.class, int.class, int.class).invoke(null, target, targetX, 82, targetZ);
                helper.getMethod("setup", ServerLevel.class, int.class, int.class, int.class).invoke(null, source, 0, 82, -3);
                if (crumbling) helper.getMethod("setSpeed", ServerLevel.class, int.class, int.class, int.class, int.class)
                    .invoke(null, target, targetX, 82, targetZ, 0);
            } else {
                wall(target, z, Blocks.RED_CONCRETE.defaultBlockState());
            }
        }
        request = token;
        PortalSmokeSupport.write("scene-ready.txt", token);
    }

    private static void wall(ServerLevel level, int z, BlockState state) {
        for (int x = -6; x <= 6; x++) for (int y = 77; y <= 87; y++) {
            level.setBlockAndUpdate(new BlockPos(x, y, z), state);
        }
    }

    private static void configure(Portal portal, net.minecraft.resources.ResourceKey<Level> dimension, Vec3 from, Vec3 to) {
        portal.setOriginPos(from);
        portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 6, 6);
        portal.setDestinationDimension(dimension);
        portal.setDestination(to);
    }

    private static void add(ServerLevel level, Entity entity) {
        level.addFreshEntity(entity);
        fixtures.add(entity);
    }

    @SubscribeEvent
    public static void beforeTick(ServerTickEvent.Pre event) {
        if (PortalSmokeSupport.enabled()) started = System.nanoTime();
    }

    @SubscribeEvent
    public static void afterTick(ServerTickEvent.Post event) {
        if (!PortalSmokeSupport.enabled() || player == null) return;
        try {
            String next = PortalSmokeSupport.read("scene-request.txt");
            if (!next.isEmpty() && !next.equals(request)) setup(next);
            if (scene.startsWith("particle-")) {
                ServerLevel target = player.server.getLevel(Level.NETHER);
                PacketRedirection.sendRedirectedMessage(player, Level.NETHER,
                    new ClientboundLevelParticlesPacket(new DustParticleOptions(new Vector3f(1, 0, 0), 3),
                        true, 0, 82, particleZ, 2, 2, 0.02f, 0, 250));
            }
            if (scene.startsWith("create-")) {
                ServerLevel target = player.server.getLevel(Level.NETHER);
                ServerLevel source = player.server.getLevel(Level.OVERWORLD);
                int z = scene.endsWith("-clipped") ? 1 : -3;
                int x = scene.equals("create-nested") ? 28 : scene.startsWith("create-crumbling-") ? 0 : -4;
                var describe = Class.forName("qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeCreateScene")
                    .getMethod("describeServerScene", ServerLevel.class, int.class, int.class, int.class);
                PortalSmokeSupport.write("create-server.json", new Gson().toJson(Map.of(
                    "scene", request,
                    "target", describe.invoke(null, target, x, 82, z),
                    "source", describe.invoke(null, source, 0, 82, -3))));
                // Static target isolates the damage overlay from ordinary rotating geometry.
                int crackStage = scene.equals("create-crumbling-damaged") || scene.equals("create-crumbling-clipped") ? 9 : -1;
                // Vanilla only sends this packet to players physically in that dimension.
                // Route it to the observer's remote client world, like other portal-visible effects.
                PacketRedirection.sendRedirectedMessage(player, Level.NETHER,
                    new ClientboundBlockDestructionPacket(78231, new BlockPos(x, 82, z + 2), crackStage));
            }
            if (PortalSmokeSupport.exists("crossing-request.txt") && player.level().dimension().equals(Level.NETHER)) {
                PortalSmokeSupport.write("crossing-server-pass.txt", "Server observed the player cross into Nether through the portal\n");
            }
            if (!PortalSmokeSupport.exists("visual-pass.txt") || timings.size() >= PortalSmokeSupport.samples()) return;
            timings.add((System.nanoTime() - started) / 1_000_000.0);
            if (timings.size() == PortalSmokeSupport.samples()) {
                PortalSmokeSupport.metrics("server", timings);
                PortalSmokeSupport.write("server-pass.txt", "Live portal server tick measurements complete\n");
            }
        } catch (Throwable e) {
            PortalSmokeSupport.write("server-fail.txt", e.toString());
        }
    }
}
