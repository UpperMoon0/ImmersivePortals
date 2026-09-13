package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.entity.EntitySubLevelUtil;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;

import java.util.UUID;

/**
 * Dedicated-server half of the Sable stacked-dimension regression test.
 *
 * <p>This intentionally uses a real Sable physics body, Create's real SeatEntity resolved
 * from the runtime registry, and the connected player's real {@link ServerPlayer}. The body
 * is deliberately tall so its origin crosses the seam before its complete bounds do. After
 * returning from Nether to Overworld the test leaves gravity and velocity untouched, forcing
 * the body to fall back through the seam before exercising a real client dismount.</p>
 */
public final class SableDimensionStackDedicatedServerTest {
    private static final int LOGIN_SETTLE_TICKS = 40;
    private static final int TIMEOUT_TICKS = 1200;
    private static final double CROSSING_SPEED = 80.0;
    private static final double RETURN_SPEED = 18.0;
    private static final int BODY_HEIGHT = 6;

    private enum Phase {
        WAIT_FOR_LOGIN_SETTLE,
        HOLD_IN_SOURCE,
        WAIT_FOR_FIRST_CROSSING,
        HOLD_IN_DESTINATION,
        WAIT_FOR_RETURN,
        WAIT_FOR_GRAVITY_RECROSS,
        WAIT_FOR_DISMOUNT,
        DONE
    }

    private static ServerPlayer player;
    private static Phase phase = Phase.WAIT_FOR_LOGIN_SETTLE;
    private static int ticks;
    private static int phaseTicks;
    private static UUID subLevelId;
    private static UUID vehicleId;
    private static Entity recrossedSeat;
    private static ServerLevel overworld;
    private static ServerLevel nether;
    private static Vector3d heldPosition;

    private SableDimensionStackDedicatedServerTest() {}

    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!SableDimensionStackIntegrationMarkers.enabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) return;

        player = serverPlayer;
        phase = Phase.WAIT_FOR_LOGIN_SETTLE;
        ticks = 0;
        phaseTicks = 0;
        subLevelId = null;
        vehicleId = null;
        recrossedSeat = null;
        overworld = null;
        nether = null;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SableDimensionStackIntegrationMarkers.enabled() || player == null || phase == Phase.DONE) return;

        try {
            ticks++;
            phaseTicks++;
            if (ticks > TIMEOUT_TICKS) {
                fail("timeout phase=" + phase + diagnosticState(), null);
                return;
            }

            switch (phase) {
                case WAIT_FOR_LOGIN_SETTLE -> {
                    if (phaseTicks < LOGIN_SETTLE_TICKS) return;
                    setupRealCrossingScenario();
                    phase = Phase.HOLD_IN_SOURCE;
                    phaseTicks = 0;
                }
                case HOLD_IN_SOURCE -> holdAndStartFirstCrossing();
                case WAIT_FOR_FIRST_CROSSING -> verifyFirstCrossingOrWait();
                case HOLD_IN_DESTINATION -> holdAndStartReturn();
                case WAIT_FOR_RETURN -> verifyReturnOrWait();
                case WAIT_FOR_GRAVITY_RECROSS -> verifyGravityRecrossOrWait();
                case WAIT_FOR_DISMOUNT -> verifyDismountOrWait();
                case DONE -> { }
            }
        }
        catch (Throwable error) {
            fail("exception phase=" + phase + diagnosticState(), error);
        }
    }

    private static void setupRealCrossingScenario() {
        overworld = player.server.getLevel(Level.OVERWORLD);
        nether = player.server.getLevel(Level.NETHER);
        require(overworld != null, "overworld unavailable");
        require(nether != null, "nether unavailable");

        ServerSubLevelContainer sourceContainer = requireContainer(overworld);
        requireContainer(nether);

        VerticalConnectingPortal.connectMutually(Level.OVERWORLD, Level.NETHER, false);
        require(VerticalConnectingPortal.getConnectingPortal(overworld, VerticalConnectingPortal.ConnectorType.floor) != null,
            "overworld floor connector was not created");
        require(VerticalConnectingPortal.getConnectingPortal(nether, VerticalConnectingPortal.ConnectorType.ceil) != null,
            "nether ceiling connector was not created");

        Pose3d pose = new Pose3d();
        pose.position().set(0.0, overworld.getMinBuildHeight() + 2.0, 0.0);
        ServerSubLevel subLevel = (ServerSubLevel) sourceContainer.allocateNewSubLevel(pose);
        LevelPlot plot = subLevel.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());

        ResourceLocation seatBlockId = ResourceLocation.fromNamespaceAndPath("create", "red_seat");
        ResourceLocation seatEntityId = ResourceLocation.fromNamespaceAndPath("create", "seat");
        require(BuiltInRegistries.BLOCK.containsKey(seatBlockId), "Create red seat block is missing from E2E runtime");
        require(BuiltInRegistries.ENTITY_TYPE.containsKey(seatEntityId), "Create seat entity is missing from E2E runtime");
        Block seatBlock = BuiltInRegistries.BLOCK.get(seatBlockId);
        plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, seatBlock.defaultBlockState(), 3);
        for (int y = 1; y < BODY_HEIGHT; y++) {
            plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(0, y, 0), Blocks.STONE.defaultBlockState(), 3);
        }
        subLevel.updateLastPose();
        subLevel.updateBoundingBox();
        subLevelId = subLevel.getUniqueId();

        BlockPos plotCenter = plot.getCenterBlock();
        EntityType<?> seatType = BuiltInRegistries.ENTITY_TYPE.get(seatEntityId);
        Entity vehicle = seatType.create(overworld);
        require(vehicle != null, "could not construct Create SeatEntity");
        vehicle.setPos(plotCenter.getX() + 0.5, plotCenter.getY(), plotCenter.getZ() + 0.5);
        require(overworld.addFreshEntity(vehicle), "could not add Create SeatEntity to Sable plot");
        require(Sable.HELPER.getContaining(vehicle) == subLevel, "Create seat was not retained inside the source Sable sublevel");
        require(!EntitySubLevelUtil.shouldKick(vehicle), "Create seat unexpectedly is not Sable-retained");
        require(EntitySubLevelUtil.shouldKick(player), "player unexpectedly uses Sable retained-entity handling");
        vehicleId = vehicle.getUUID();

        require(player.startRiding(vehicle, true), "server player could not mount Create SeatEntity");
        heldPosition = new Vector3d(subLevel.logicalPose().position());
        setLinearVelocity(requireHandle(subLevel), new Vector3d());
    }

    private static void holdAndStartFirstCrossing() {
        ServerSubLevel source = findSubLevel(requireContainer(overworld), subLevelId);
        require(source != null, "source Sable sublevel disappeared before first crossing");
        require(player.serverLevel() == overworld, "rider left source before first crossing began");
        require(player.getVehicle() != null && player.getVehicle().getUUID().equals(vehicleId),
            "riding graph broke while held in source");

        RigidBodyHandle handle = requireHandle(source);
        if (!SableDimensionStackIntegrationMarkers.exists("client-source.txt")) {
            // Zeroing velocity alone still permits gravity to move the body during
            // the next physics step. Pin the pose until the client has observed it.
            handle.teleport(heldPosition, source.logicalPose().orientation());
            setLinearVelocity(handle, new Vector3d());
            return;
        }

        setLinearVelocity(handle, new Vector3d(0.0, -CROSSING_SPEED, 0.0));
        phase = Phase.WAIT_FOR_FIRST_CROSSING;
        phaseTicks = 0;
    }

    private static void verifyFirstCrossingOrWait() {
        ServerSubLevel source = findSubLevel(requireContainer(overworld), subLevelId);
        ServerSubLevel destination = findSubLevel(requireContainer(nether), subLevelId);
        if (destination == null) return;

        require(source == null, "source Sable sublevel still exists after destination reconstruction");
        require(destination.getPlot().getEmbeddedLevelAccessor().getBlockState(new BlockPos(0, BODY_HEIGHT - 1, 0)).is(Blocks.STONE),
            "serialized tall Sable block payload did not survive first crossing");

        Entity destinationVehicle = nether.getEntity(vehicleId);
        require(destinationVehicle != null, "retained vehicle did not migrate to the destination level");
        require(overworld.getEntity(vehicleId) == null, "duplicate vehicle remained in source");
        require(destinationVehicle.level() == nether, "retained vehicle is attached to the wrong level after crossing");
        require(player.serverLevel() == nether, "rider did not migrate to the destination level");
        require(player.getVehicle() != null, "rider lost its vehicle after first crossing");
        require(player.getVehicle().getUUID().equals(vehicleId), "rider was attached to a different vehicle after first crossing");
        require(destinationVehicle.getPassengers().contains(player), "destination vehicle does not contain the migrated rider");

        Vector3d velocity = requireHandle(destination).getLinearVelocity(new Vector3d());
        require(Double.isFinite(velocity.x) && Double.isFinite(velocity.y) && Double.isFinite(velocity.z),
            "migrated Sable body has non-finite velocity");

        setLinearVelocity(requireHandle(destination), new Vector3d());
        heldPosition = new Vector3d(destination.logicalPose().position());
        phase = Phase.HOLD_IN_DESTINATION;
        phaseTicks = 0;
    }

    private static void holdAndStartReturn() {
        ServerSubLevel destination = findSubLevel(requireContainer(nether), subLevelId);
        require(destination != null, "destination Sable sublevel disappeared while waiting for client synchronization");
        require(player.serverLevel() == nether, "rider left destination before reverse crossing began");
        require(player.getVehicle() != null && player.getVehicle().getUUID().equals(vehicleId),
            "riding graph broke while held in destination");

        RigidBodyHandle handle = requireHandle(destination);
        if (!SableDimensionStackIntegrationMarkers.exists("client-destination.txt")) {
            handle.teleport(heldPosition, destination.logicalPose().orientation());
            setLinearVelocity(handle, new Vector3d());
            return;
        }

        setLinearVelocity(handle, new Vector3d(0.0, RETURN_SPEED, 0.0));
        phase = Phase.WAIT_FOR_RETURN;
        phaseTicks = 0;
    }

    private static void verifyReturnOrWait() {
        ServerSubLevel returned = findSubLevel(requireContainer(overworld), subLevelId);
        if (returned == null) return;

        require(findSubLevel(requireContainer(nether), subLevelId) == null,
            "destination Sable sublevel still exists after reverse crossing");
        require(returned.getPlot().getEmbeddedLevelAccessor().getBlockState(new BlockPos(0, BODY_HEIGHT - 1, 0)).is(Blocks.STONE),
            "serialized tall Sable block payload did not survive round trip");

        Entity returnedVehicle = overworld.getEntity(vehicleId);
        require(returnedVehicle != null, "retained vehicle did not survive round trip");
        require(nether.getEntity(vehicleId) == null, "duplicate vehicle remained in Nether");
        require(returnedVehicle.level() == overworld, "returned vehicle has wrong level");
        require(player.serverLevel() == overworld, "rider did not return to the source level");
        require(player.getVehicle() != null && player.getVehicle().getUUID().equals(vehicleId),
            "rider/vehicle relation did not survive round trip");
        require(returnedVehicle.getPassengers().contains(player),
            "returned vehicle does not contain the original rider");

        // Deliberately do not teleport, park, or zero the returned body. Its retained
        // upward velocity must decay under normal Overworld gravity and make it fall
        // back through the floor connector, reproducing the real seam-bounce report.
        phase = Phase.WAIT_FOR_GRAVITY_RECROSS;
        phaseTicks = 0;
    }

    private static void verifyGravityRecrossOrWait() {
        ServerSubLevel recrossed = findSubLevel(requireContainer(nether), subLevelId);
        if (recrossed == null) return;

        require(findSubLevel(requireContainer(overworld), subLevelId) == null,
            "Overworld sublevel remained after gravity-driven recross");
        Entity seat = nether.getEntity(vehicleId);
        require(seat != null, "Create seat disappeared during gravity-driven recross");
        require(overworld.getEntity(vehicleId) == null, "duplicate Create seat remained in Overworld after recross");
        require(player.serverLevel() == nether, "rider did not follow gravity-driven recross to Nether");
        require(player.getVehicle() != null && player.getVehicle().getUUID().equals(vehicleId),
            "rider/seat relation broke during gravity-driven recross");
        require(seat.getPassengers().contains(player),
            "recrossed Create seat does not contain the original rider");
        recrossedSeat = seat;

        phase = Phase.WAIT_FOR_DISMOUNT;
        phaseTicks = 0;
    }

    private static void verifyDismountOrWait() {
        if (!SableDimensionStackIntegrationMarkers.exists("client-recross.txt")) return;
        if (player.getVehicle() != null) return;
        if (!SableDimensionStackIntegrationMarkers.exists("client-dismount.txt")) return;

        // Create deliberately discards SeatEntity once it has no passengers. Keep
        // the observed instance to verify its graph even if it has left the lookup.
        require(recrossedSeat != null && !recrossedSeat.getPassengers().contains(player),
            "Create seat still contains player after dismount");

        phase = Phase.DONE;
        SableDimensionStackIntegrationMarkers.serverPass(
            "tall Sable body crossed Overworld->Nether->Overworld, fell back through under gravity, and real Create SeatEntity rider dismounted cleanly"
        );
    }

    private static ServerSubLevelContainer requireContainer(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        require(container != null, "Sable container missing for " + level.dimension().location());
        return container;
    }

    private static ServerSubLevel findSubLevel(ServerSubLevelContainer container, UUID id) {
        if (id == null) return null;
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (id.equals(subLevel.getUniqueId())) return subLevel;
        }
        return null;
    }

    private static RigidBodyHandle requireHandle(ServerSubLevel subLevel) {
        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
        require(handle != null && handle.isValid(), "Sable physics handle unavailable for " + subLevel.getUniqueId());
        return handle;
    }

    private static void setLinearVelocity(RigidBodyHandle handle, Vector3d target) {
        Vector3d currentLinear = handle.getLinearVelocity(new Vector3d());
        Vector3d currentAngular = handle.getAngularVelocity(new Vector3d());
        handle.addLinearAndAngularVelocity(
            new Vector3d(target).sub(currentLinear),
            new Vector3d(currentAngular).negate()
        );
    }

    private static String diagnosticState() {
        if (player == null) return " player=null";
        Vec3 position = player.position();
        return " playerDim=" + player.level().dimension().location()
            + " playerPos=" + position
            + " riding=" + (player.getVehicle() == null ? "none" : player.getVehicle().getUUID())
            + " subLevel=" + subLevelId
            + " vehicle=" + vehicleId;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }

    private static void fail(String detail, Throwable error) {
        phase = Phase.DONE;
        SableDimensionStackIntegrationMarkers.serverFail(detail, error);
    }
}
