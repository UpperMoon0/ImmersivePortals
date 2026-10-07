package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Display;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import qouteall.imm_ptl.core.network.PacketRedirection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
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
    private static long fixtureGeneration;
    private static Map<String, Object> firstCrossingPose;

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

    private static void setup(String token) throws Exception {
        String previousScene = scene;
        String nextScene = token.substring(token.indexOf(':') + 1);
        boolean sameEpoch = !request.isEmpty() && request.substring(0, request.indexOf(':')).equals(token.substring(0, token.indexOf(':')));
        boolean damageTransition = previousScene.equals("create-crumbling-clean") && nextScene.equals("create-crumbling-damaged")
            || previousScene.equals("create-crumbling-damaged") && nextScene.equals("create-crumbling-restored");
        scene = nextScene;
        if (sameEpoch && damageTransition) {
            // Change only the damage packet. Preserve the portal, camera, blocks and BEs
            // so an unrelated rebuild/re-teleport cannot masquerade as a crack overlay.
            request = token;
            writeSceneWitness(token);
            PortalSmokeSupport.write("scene-ready.txt", token);
            return;
        }
        fixtureGeneration++;
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
        if (scene.startsWith("create-nested") || previousScene.startsWith("create-nested")) {
            for (int x = 24; x <= 40; x++) for (int y = 76; y <= 88; y++) for (int z = -5; z <= 8; z++) {
                target.setBlockAndUpdate(new BlockPos(x, y, z), (scene.startsWith("create-nested") && z == -4)
                    ? Blocks.LIME_CONCRETE.defaultBlockState() : (scene.equals("create-nested") && z == 1)
                    ? Blocks.RED_CONCRETE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
        boolean straddling = scene.startsWith("entity-") || scene.startsWith("particle-");
        if (straddling) {
            // An oblique view separates excluded and retained fragments on screen. Keep
            // a broad green backdrop behind rays on both sides of the clipping boundary.
            for (int x = -32; x <= 32; x++) for (int y = 76; y <= 88; y++) for (int z = -12; z <= 8; z++) {
                target.setBlockAndUpdate(new BlockPos(x, y, z), z == -10
                    ? Blocks.LIME_CONCRETE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }
        player.teleportTo(source, straddling ? 4 : 0, 80.38, 4, straddling ? 135 : 180, 0);
        boolean visible = scene.endsWith("-visible");
        boolean background = scene.endsWith("-background");
        int z = visible ? -1 : 1;
        if (scene.startsWith("mirror")) {
            if (!background && !visible) wall(source, PortalSmokeMirrorGeometry.excludedWallZ(), Blocks.RED_CONCRETE.defaultBlockState());
            wall(source, 6, (visible ? Blocks.RED_CONCRETE : Blocks.LIME_CONCRETE).defaultBlockState());
            Mirror mirror = Mirror.ENTITY_TYPE.create(source);
            // The spectator observer's reflected head is legitimate foreground
            // geometry. This controlled wall/depth fixture excludes that one
            // player through the mirror's existing, synced per-portal setting.
            // The mirror is discarded on scene change; no global option changes.
            mirror.setDoRenderPlayer(false);
            configure(mirror, Level.OVERWORLD, new Vec3(0, 82, 0), new Vec3(0, 82, 0));
            add(source, mirror);
        } else {
            Portal portal = Portal.ENTITY_TYPE.create(source);
            configure(portal, Level.NETHER, new Vec3(0, 82, 0), new Vec3(0, 82, 0));
            if (straddling) portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 12, 10);
            add(source, portal);
            if (scene.startsWith("nested") || scene.startsWith("create-nested")) {
                wall(target, -4, Blocks.RED_CONCRETE.defaultBlockState());
                if (!background) wall(end, visible ? -1 : 1, Blocks.RED_CONCRETE.defaultBlockState());
                Portal nested = Portal.ENTITY_TYPE.create(target);
                configure(nested, scene.startsWith("create-nested") ? Level.NETHER : Level.END,
                    new Vec3(0, 82, -1), new Vec3(scene.startsWith("create-nested") ? 32 : 0, 82, 0));
                add(target, nested);
                if (scene.startsWith("create-nested")) {
                    // Re-enter the same Nether LevelRenderer to test Flywheel's per-level context restoration.
                    var setup = Class.forName("qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeCreateScene")
                        .getMethod("setup", ServerLevel.class, int.class, int.class, int.class);
                    if (!background) setup.invoke(null, target, 28, 82, -3);
                    setup.invoke(null, source, 0, 82, -3);
                }
            } else if (scene.startsWith("cutout-")) {
                wall(target, z, Blocks.OAK_LEAVES.defaultBlockState());
            } else if (scene.startsWith("translucent-")) {
                wall(target, z, Blocks.RED_STAINED_GLASS.defaultBlockState());
            } else if (scene.startsWith("block-entity-")) {
                wall(target, z, Blocks.RED_SHULKER_BOX.defaultBlockState());
            } else if (scene.startsWith("entity-")) {
                // A camera-facing thin panel avoids a closed cube's retained back faces
                // masking the excluded-side oracle. Its CPU-culling origin is retained,
                // while its geometry spans both sides of z=0 in the clipped case.
                Display.BlockDisplay display = EntityType.BLOCK_DISPLAY.create(target);
                display.load(TagParser.parseTag("{block_state:{Name:\"minecraft:red_concrete\"},"
                    + "transformation:{translation:[-2.846105f,-3.0f,2.810749f],scale:[8.0f,6.0f,0.05f],"
                    + "left_rotation:[0.0f,0.38268343f,0.0f,0.9238795f],right_rotation:[0.0f,0.0f,0.0f,1.0f]},"
                    + "brightness:{block:15,sky:15},width:12.0f,height:12.0f,view_range:4.0f}"));
                display.setPos(0, 82, visible ? -6 : -0.25);
                display.setNoGravity(true);
                PortalSmokeSupport.write("entity-panel-id.txt", display.getUUID().toString());
                add(target, display);
            } else if (scene.startsWith("particle-")) {
                // Client creates stationary, oversized real dust billboards in this
                // remote world. Their centers remain on the CPU-accepted side.
            } else if (scene.startsWith("create-")) {
                // The optional helper is kept separate from this no-Sable discovery boundary.
                Class<?> helper = Class.forName("qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeCreateScene");
                boolean crumbling = scene.startsWith("create-crumbling-");
                int targetX = crumbling ? 0 : -4;
                int targetZ = scene.endsWith("-clipped") ? 1 : -3;
                if (!background) helper.getMethod("setup", ServerLevel.class, int.class, int.class, int.class).invoke(null, target, targetX, 82, targetZ);
                helper.getMethod("setup", ServerLevel.class, int.class, int.class, int.class).invoke(null, source, 0, 82, -3);
                if (crumbling) helper.getMethod("setSpeed", ServerLevel.class, int.class, int.class, int.class, int.class)
                    .invoke(null, target, targetX, 82, targetZ, 0);
            } else if (!background) {
                wall(target, z, Blocks.RED_CONCRETE.defaultBlockState());
            }
        }
        request = token;
        writeSceneWitness(token);
        PortalSmokeSupport.write("scene-ready.txt", token);
    }

    private static void writeSceneWitness(String token) {
        writeSceneWitness(token, "scene-world-witness.json");
    }

    private static void writeSceneWitness(String token, String file) {
        Map<String, Object> worlds = new java.util.LinkedHashMap<>();
        for (var dimension : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
            ServerLevel level = player.server.getLevel(dimension);
            Map<String, String> blocks = new java.util.LinkedHashMap<>();
            int[] blockXs = token.contains("create-nested") && dimension == Level.NETHER
                ? new int[]{-1, 0, 28, 31, 32, 33, 40} : new int[]{-1, 0};
            for (int x : blockXs) for (int z : new int[]{-10, -4, -1, 1}) {
                BlockPos pos = new BlockPos(x, 82, z);
                var chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                blocks.put(x + ",82," + z, chunk == null ? "chunk absent" : chunk.getBlockState(pos).toString());
            }
            Map<String, Object> state = new java.util.LinkedHashMap<>();
            state.put("gameTime", level.getGameTime());
            state.put("blocks", blocks);
            if (token.contains("create-nested") && dimension == Level.NETHER) {
                Map<String, Object> tracking = new java.util.LinkedHashMap<>();
                for (int x : new int[]{0, 1, 2, 3}) for (int z : new int[]{-1, 0}) {
                    var records = qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking.getWatchRecordForChunk(dimension, x, z);
                    var record = records == null ? null : records.get(player);
                    var holder = ((qouteall.imm_ptl.core.ducks.IEChunkMap) level.getChunkSource().chunkMap)
                        .ip_getChunkHolder(net.minecraft.world.level.ChunkPos.asLong(x, z));
                    Map<String, Object> chunk = new java.util.LinkedHashMap<>();
                    chunk.put("watch", record == null ? "absent" : record.toString());
                    chunk.put("lastWatchGeneration", record == null ? -1 : record.lastWatchGeneration);
                    chunk.put("boundary", record != null && record.isBoundary);
                    chunk.put("holder", holder == null ? "absent" : holder.getFullStatus().toString());
                    chunk.put("tickingChunk", holder != null && holder.getTickingChunk() != null);
                    tracking.put(x + "," + z, chunk);
                }
                state.put("tracking", tracking);
            }
            worlds.put(dimension.location().toString(), state);
        }
        var loaders = new ArrayList<String>();
        qouteall.imm_ptl.core.chunk_loading.ChunkVisibility.foreachBaseChunkLoaders(player, loader -> loaders.add(loader.toString()));
        PortalSmokeSupport.write(file, new Gson().toJson(Map.of(
            "request", token, "fixtureGeneration", fixtureGeneration, "worlds", worlds,
            "baseChunkLoaders", loaders,
            "serverPerformance", qouteall.imm_ptl.core.chunk_loading.ServerPerformanceMonitor.getLevel().toString(),
            "clientPerformance", qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking.getPlayerInfo(player).performanceLevel.toString())));
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
            if (scene.startsWith("create-nested") && player.tickCount % 20 == 0) {
                writeSceneWitness(request, "scene-live-world-witness.json");
            }
            if (scene.startsWith("create-")) {
                ServerLevel target = player.server.getLevel(Level.NETHER);
                ServerLevel source = player.server.getLevel(Level.OVERWORLD);
                int z = scene.endsWith("-clipped") ? 1 : -3;
                int x = scene.startsWith("create-nested") ? 28 : scene.startsWith("create-crumbling-") ? 0 : -4;
                var describe = Class.forName("qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeCreateScene")
                    .getMethod("describeServerScene", ServerLevel.class, int.class, int.class, int.class);
                // Static target isolates the damage overlay from ordinary rotating geometry.
                int crackStage = scene.equals("create-crumbling-damaged") || scene.equals("create-crumbling-clipped") ? 9 : -1;
                // Vanilla only sends this packet to players physically in that dimension.
                // Route it to the observer's remote client world, like other portal-visible effects.
                PacketRedirection.sendRedirectedMessage(player, Level.NETHER,
                    new ClientboundBlockDestructionPacket(78231, new BlockPos(x, 82, z + 2), crackStage));
                PortalSmokeSupport.write("create-server.json", new Gson().toJson(Map.of(
                    "scene", request,
                    "target", describe.invoke(null, target, x, 82, z),
                    "source", describe.invoke(null, source, 0, 82, -3),
                    "crumbling_control", Map.of("fixture_generation", fixtureGeneration, "stage", crackStage,
                        "position", List.of(x, 82, z + 2), "observer", player.getUUID().toString(), "packet_sent", true,
                        "block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(target.getBlockState(new BlockPos(x, 82, z + 2)).getBlock()).toString()))));
            }
            if (PortalSmokeSupport.exists("crossing-request.txt") && player.level().dimension().equals(Level.NETHER)) {
                Vec3 eye = player.getEyePosition();
                Map<String, Object> pose = Map.of("dimension", player.level().dimension().location().toString(),
                    "player_uuid", player.getUUID().toString(), "position", List.of(player.getX(), player.getY(), player.getZ()),
                    "eye", List.of(eye.x, eye.y, eye.z), "tick", player.tickCount);
                if (firstCrossingPose == null) firstCrossingPose = pose;
                PortalSmokeSupport.write("crossing-server-evidence.json", new Gson().toJson(Map.of("first_destination", firstCrossingPose, "current", pose)));
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
