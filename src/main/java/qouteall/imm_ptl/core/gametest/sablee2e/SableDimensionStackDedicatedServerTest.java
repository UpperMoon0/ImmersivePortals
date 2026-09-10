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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;

import java.util.UUID;

/**
 * Dedicated-server half of the Sable stacked-dimension regression test.
 *
 * <p>This intentionally uses a real Sable physics body, a real retained vanilla minecart,
 * and the connected player's real {@link ServerPlayer}. The minecart remains in Sable plot
 * space while Sable kicks the rider into logical world space, reproducing the passenger graph
 * that originally split at a dimension-stack boundary.</p>
 */
@EventBusSubscriber(modid = "immersive_portals")
public final class SableDimensionStackDedicatedServerTest {
    private static final int LOGIN_SETTLE_TICKS = 40;
    private static final int DESTINATION_HOLD_TICKS = 60;
    private static final int TIMEOUT_TICKS = 1200;
    private static final double CROSSING_SPEED = 80.0;

    private enum Phase {
        WAIT_FOR_LOGIN_SETTLE,
        WAIT_FOR_FIRST_CROSSING,
        HOLD_IN_DESTINATION,
        WAIT_FOR_RETURN,
        DONE
    }

    private static ServerPlayer player;
    private static Phase phase = Phase.WAIT_FOR_LOGIN_SETTLE;
    private static int ticks;
    private static int phaseTicks;
    private static UUID subLevelId;
    private static UUID vehicleId;
    private static ServerLevel overworld;
    private static ServerLevel nether;

    private SableDimensionStackDedicatedServerTest() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!SableDimensionStackIntegrationMarkers.enabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer serverPlayer)) return;

        player = serverPlayer;
        phase = Phase.WAIT_FOR_LOGIN_SETTLE;
        ticks = 0;
        phaseTicks = 0;
        subLevelId = null;
        vehicleId = null;
        overworld = null;
        nether = null;
    }

    @SubscribeEvent
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
                    phase = Phase.WAIT_FOR_FIRST_CROSSING;
                    phaseTicks = 0;
                }
                case WAIT_FOR_FIRST_CROSSING -> verifyFirstCrossingOrWait();
                case HOLD_IN_DESTINATION -> holdAndStartReturn();
                case WAIT_FOR_RETURN -> verifyReturnOrWait();
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
        plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
        subLevel.updateLastPose();
        subLevelId = subLevel.getUniqueId();

        BlockPos plotCenter = plot.getCenterBlock();
        Minecart vehicle = new Minecart(overworld,
            plotCenter.getX() + 0.5,
            plotCenter.getY() + 1.0,
            plotCenter.getZ() + 0.5);
        require(overworld.addFreshEntity(vehicle), "could not add retained minecart to Sable plot");
        require(Sable.HELPER.getContaining(vehicle) == subLevel, "minecart was not retained inside the source Sable sublevel");
        require(!EntitySubLevelUtil.shouldKick(vehicle), "minecart unexpectedly is not Sable-retained");
        require(EntitySubLevelUtil.shouldKick(player), "player unexpectedly uses Sable retained-entity handling");
        vehicleId = vehicle.getUUID();

        require(player.startRiding(vehicle, true), "server player could not mount retained Sable minecart");

        RigidBodyHandle handle = requireHandle(subLevel);
        setLinearVelocity(handle, new Vector3d(0.0, -CROSSING_SPEED, 0.0));
    }

    private static void verifyFirstCrossingOrWait() {
        ServerSubLevel source = findSubLevel(requireContainer(overworld), subLevelId);
        ServerSubLevel destination = findSubLevel(requireContainer(nether), subLevelId);
        if (destination == null) return;

        require(source == null, "source Sable sublevel still exists after destination reconstruction");
        require(destination.getPlot().getEmbeddedLevelAccessor().getBlockState(BlockPos.ZERO).is(Blocks.STONE),
            "serialized Sable block payload did not survive first crossing");

        Entity destinationVehicle = nether.getEntity(vehicleId);
        require(destinationVehicle != null, "retained vehicle did not migrate to the destination level");
        require(destinationVehicle.level() == nether, "retained vehicle is attached to the wrong level after crossing");
        require(player.serverLevel() == nether, "rider did not migrate to the destination level");
        require(player.getVehicle() != null, "rider lost its vehicle after first crossing");
        require(player.getVehicle().getUUID().equals(vehicleId), "rider was attached to a different vehicle after first crossing");
        require(destinationVehicle.getPassengers().contains(player), "destination vehicle does not contain the migrated rider");

        Vector3d velocity = requireHandle(destination).getLinearVelocity(new Vector3d());
        require(Double.isFinite(velocity.x) && Double.isFinite(velocity.y) && Double.isFinite(velocity.z),
            "migrated Sable body has non-finite velocity");

        setLinearVelocity(requireHandle(destination), new Vector3d());
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
        if (phaseTicks < DESTINATION_HOLD_TICKS) {
            setLinearVelocity(handle, new Vector3d());
            return;
        }

        setLinearVelocity(handle, new Vector3d(0.0, CROSSING_SPEED, 0.0));
        phase = Phase.WAIT_FOR_RETURN;
        phaseTicks = 0;
    }

    private static void verifyReturnOrWait() {
        ServerSubLevel returned = findSubLevel(requireContainer(overworld), subLevelId);
        if (returned == null) return;

        require(findSubLevel(requireContainer(nether), subLevelId) == null,
            "destination Sable sublevel still exists after reverse crossing");
        require(returned.getPlot().getEmbeddedLevelAccessor().getBlockState(BlockPos.ZERO).is(Blocks.STONE),
            "serialized Sable block payload did not survive round trip");

        Entity returnedVehicle = overworld.getEntity(vehicleId);
        require(returnedVehicle != null, "retained vehicle did not survive round trip");
        require(player.serverLevel() == overworld, "rider did not return to the source level");
        require(player.getVehicle() != null && player.getVehicle().getUUID().equals(vehicleId),
            "rider/vehicle relation did not survive round trip");
        require(returnedVehicle.getPassengers().contains(player),
            "returned vehicle does not contain the original rider");

        setLinearVelocity(requireHandle(returned), new Vector3d());
        phase = Phase.DONE;
        SableDimensionStackIntegrationMarkers.serverPass(
            "real Sable physics crossed Overworld->Nether->Overworld; sublevel block payload, retained minecart, and player riding graph survived"
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
