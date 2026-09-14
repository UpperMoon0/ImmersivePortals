package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3dc;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.UUID;

/** Real-client observer for the Sable cross-dimension continuity regression test. */
public final class SableDimensionStackDedicatedClientTest {
    private static final String DEDICATED_ADDRESS = "127.0.0.1:" + System.getenv().getOrDefault("IP_SABLE_E2E_PORT", "25565");
    private static final int TIMEOUT_TICKS = 1200;
    private static final int RIDING_SYNC_GRACE_TICKS = 120;
    private static final int MAX_OVERLAP_TICKS = 20;
    private static final int EXPECTED_SEAM_TRANSITIONS = 3;
    private static final int MIN_FULL_SYNC_SNAPSHOTS = 2;
    private static final int MIN_GRAFTED_HISTORY_SNAPSHOTS = 3;
    private static final double MAX_BODY_RIDER_DISTANCE = 32.0;
    private static final double MIN_REMOTE_OBSERVED_MOVEMENT = 0.30;
    private static final float EXPECTED_PORTAL_YAW_DELTA = 90.0f;
    private static final float YAW_TOLERANCE_DEGREES = 3.0f;

    private enum Phase {
        CONNECT,
        WAIT_FOR_SOURCE_RIDE,
        WAIT_FOR_DESTINATION_RIDE,
        WAIT_FOR_RETURN_RIDE,
        WAIT_FOR_GRAVITY_RECROSS,
        WAIT_FOR_DISMOUNT,
        WAIT_FOR_REMOTE_SOURCE,
        WAIT_FOR_REMOTE_MOVEMENT,
        WAIT_FOR_SERVER_PASS,
        DONE
    }

    private static Phase phase = Phase.CONNECT;
    private static int ticks;
    private static int phaseTicks;
    private static boolean connectionRequested;
    private static UUID vehicleId;
    private static UUID subLevelId;
    private static int ridingSyncTicks;
    private static int overlapTicks;
    private static int seamTransitions;
    private static ResourceKey<Level> lastObservedDimension;
    private static double remoteSourceX = Double.NaN;
    private static float sourceWorldYaw;

    private SableDimensionStackDedicatedClientTest() {}

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!SableDimensionStackIntegrationMarkers.enabled() || phase == Phase.DONE) return;

        try {
            ticks++;
            phaseTicks++;
            if (ticks > TIMEOUT_TICKS) {
                fail("timeout phase=" + phase + diagnosticState(), null);
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (phase == Phase.CONNECT) {
                connectWhenReady(minecraft);
                return;
            }
            if (minecraft.player == null || minecraft.level == null) return;

            // This invariant applies while the rider and body are expected to share their
            // canonical dimension. The final observer phase deliberately moves only the
            // player back to Overworld, so it has its own remote-world continuity checks.
            if (subLevelId != null && isRiderHandoffPhase()) {
                verifyContinuousClientOwnership(minecraft);
            }

            switch (phase) {
                case WAIT_FOR_SOURCE_RIDE -> waitForSourceRide(minecraft);
                case WAIT_FOR_DESTINATION_RIDE -> waitForDestinationRide(minecraft);
                case WAIT_FOR_RETURN_RIDE -> waitForReturnRide(minecraft);
                case WAIT_FOR_GRAVITY_RECROSS -> waitForGravityRecross(minecraft);
                case WAIT_FOR_DISMOUNT -> waitForDismount(minecraft);
                case WAIT_FOR_REMOTE_SOURCE -> waitForRemoteSource(minecraft);
                case WAIT_FOR_REMOTE_MOVEMENT -> waitForRemoteMovement(minecraft);
                case WAIT_FOR_SERVER_PASS -> waitForStableServerConfirmedRemoteMovement(minecraft);
                case CONNECT, DONE -> { }
            }
        }
        catch (Throwable error) {
            fail("exception phase=" + phase + diagnosticState(), error);
        }
    }

    private static boolean isRiderHandoffPhase() {
        return switch (phase) {
            case WAIT_FOR_SOURCE_RIDE, WAIT_FOR_DESTINATION_RIDE,
                 WAIT_FOR_RETURN_RIDE, WAIT_FOR_GRAVITY_RECROSS,
                 WAIT_FOR_DISMOUNT -> true;
            case CONNECT, WAIT_FOR_REMOTE_SOURCE, WAIT_FOR_REMOTE_MOVEMENT,
                 WAIT_FOR_SERVER_PASS, DONE -> false;
        };
    }

    private static void connectWhenReady(Minecraft minecraft) {
        if (minecraft.player != null && minecraft.level != null) {
            phase = Phase.WAIT_FOR_SOURCE_RIDE;
            phaseTicks = 0;
            return;
        }
        if (connectionRequested || minecraft.screen == null) return;

        connectionRequested = true;
        ServerAddress address = ServerAddress.parseString(DEDICATED_ADDRESS);
        ServerData server = new ServerData(
            "Immersive Portals Sable E2E",
            DEDICATED_ADDRESS,
            ServerData.Type.OTHER
        );
        ConnectScreen.startConnecting(minecraft.screen, minecraft, address, server, true, null);
        phase = Phase.WAIT_FOR_SOURCE_RIDE;
        phaseTicks = 0;
    }

    private static void waitForSourceRide(Minecraft minecraft) {
        if (!minecraft.level.dimension().equals(Level.OVERWORLD)) return;
        Entity vehicle = minecraft.player.getVehicle();
        if (vehicle == null) return;

        vehicleId = vehicle.getUUID();
        require(vehicle.getPassengers().contains(minecraft.player),
            "client vehicle did not contain local player before crossing");
        SubLevel containing = Sable.HELPER.getContaining(vehicle);
        require(containing != null, "client Create seat is not contained by a Sable sublevel");
        subLevelId = containing.getUniqueId();
        sourceWorldYaw = worldYaw(minecraft.player);
        lastObservedDimension = minecraft.level.dimension();
        overlapTicks = 0;
        seamTransitions = 0;
        remoteSourceX = Double.NaN;
        verifyContinuousClientOwnership(minecraft);

        SableDimensionStackIntegrationMarkers.acknowledge("source");
        phase = Phase.WAIT_FOR_DESTINATION_RIDE;
        phaseTicks = 0;
    }

    /**
     * Strong continuity invariant: once the client has observed the logical Sable UUID, at
     * least one copy must exist every client tick and the player's current dimension must
     * already contain it. A short source+destination overlap is valid during atomic handoff;
     * a long overlap is a leaked stale copy.
     */
    private static void verifyContinuousClientOwnership(Minecraft minecraft) {
        boolean inOverworld = hasSubLevel(Level.OVERWORLD);
        boolean inNether = hasSubLevel(Level.NETHER);
        require(inOverworld || inNether,
            "Sable sublevel disappeared from every client world during portal handoff");

        ResourceKey<Level> currentDimension = minecraft.level.dimension();
        if (currentDimension.equals(Level.OVERWORLD) || currentDimension.equals(Level.NETHER)) {
            ClientSubLevel current = getClientSubLevel(currentDimension);
            require(current != null,
                "current client dimension changed before destination Sable sublevel was synchronized");
            verifyBodyRemainsSpatiallyContinuous(minecraft, current);
        }

        if (inOverworld && inNether) {
            require(++overlapTicks <= MAX_OVERLAP_TICKS,
                "source and destination Sable client copies overlapped too long");
        }
        else {
            overlapTicks = 0;
        }

        if (lastObservedDimension != null && !lastObservedDimension.equals(currentDimension)) {
            seamTransitions++;
            require(seamTransitions <= EXPECTED_SEAM_TRANSITIONS,
                "client dimension ownership flickered/ping-ponged across the portal seam");
        }
        lastObservedDimension = currentDimension;
    }

    private static void verifyBodyRemainsSpatiallyContinuous(Minecraft minecraft, ClientSubLevel subLevel) {
        Vector3dc bodyPosition = subLevel.logicalPose().position();
        double dx = bodyPosition.x() - minecraft.player.getX();
        double dy = bodyPosition.y() - minecraft.player.getY();
        double dz = bodyPosition.z() - minecraft.player.getZ();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        require(distanceSquared <= MAX_BODY_RIDER_DISTANCE * MAX_BODY_RIDER_DISTANCE,
            "Sable body pose snapped away from its rider during portal handoff: distance=" + Math.sqrt(distanceSquared));
    }

    private static void verifyInterpolationHistory(
        ResourceKey<Level> dimension, String phaseName, int minimumSnapshots
    ) {
        ClientSubLevel subLevel = getClientSubLevel(dimension);
        require(subLevel != null, phaseName + " Sable sublevel is missing");
        int snapshots = subLevel.getInterpolator().buffer.size();
        require(snapshots >= minimumSnapshots,
            phaseName + " Sable interpolation history was reset during portal handoff: snapshots=" + snapshots
                + " minimum=" + minimumSnapshots);
    }

    /** Sable stores mounted yaw locally; the mixed getLookAngle() is the actual world-facing view. */
    private static float worldYaw(Entity entity) {
        Vec3 look = entity.getLookAngle();
        return (float) Math.toDegrees(Math.atan2(-look.x, look.z));
    }

    private static void verifyYawDelta(Minecraft minecraft, float expectedAbsDelta, String phaseName) {
        float currentWorldYaw = worldYaw(minecraft.player);
        float actualAbsDelta = Math.abs(Mth.wrapDegrees(currentWorldYaw - sourceWorldYaw));
        require(Math.abs(actualAbsDelta - expectedAbsDelta) <= YAW_TOLERANCE_DEGREES,
            phaseName + " world-facing camera yaw was not transformed exactly once: source=" + sourceWorldYaw
                + " current=" + currentWorldYaw
                + " rawLocalYaw=" + minecraft.player.getYRot()
                + " expectedAbsDelta=" + expectedAbsDelta
                + " actualAbsDelta=" + actualAbsDelta);
    }

    private static ClientSubLevel getClientSubLevel(ResourceKey<Level> dimension) {
        if (subLevelId == null || !ClientWorldLoader.getServerDimensions().contains(dimension)) return null;
        ClientLevel world = ClientWorldLoader.getWorld(dimension);
        SubLevelContainer container = SubLevelContainer.getContainer(world);
        if (container == null) return null;
        SubLevel subLevel = container.getSubLevel(subLevelId);
        return subLevel instanceof ClientSubLevel clientSubLevel ? clientSubLevel : null;
    }

    private static boolean hasSubLevel(ResourceKey<Level> dimension) {
        return getClientSubLevel(dimension) != null;
    }

    private static void waitForDestinationRide(Minecraft minecraft) {
        if (!minecraft.level.dimension().equals(Level.NETHER)) return;

        Entity vehicle = minecraft.player.getVehicle();
        if (vehicle == null) {
            require(++ridingSyncTicks <= RIDING_SYNC_GRACE_TICKS,
                "client reached Nether but riding relation did not synchronize");
            return;
        }

        require(vehicle.getUUID().equals(vehicleId),
            "client mounted a different vehicle after Overworld->Nether crossing");
        require(vehicle.getPassengers().contains(minecraft.player),
            "client vehicle passenger graph is inconsistent in Nether");
        require(hasSubLevel(Level.NETHER), "destination Sable sublevel missing in Nether");
        verifyInterpolationHistory(Level.NETHER, "first destination", MIN_FULL_SYNC_SNAPSHOTS);
        verifyYawDelta(minecraft, EXPECTED_PORTAL_YAW_DELTA, "first rotated crossing");
        SableDimensionStackIntegrationMarkers.acknowledge("destination");
        ridingSyncTicks = 0;
        phase = Phase.WAIT_FOR_RETURN_RIDE;
        phaseTicks = 0;
    }

    private static void waitForReturnRide(Minecraft minecraft) {
        if (!minecraft.level.dimension().equals(Level.OVERWORLD)) return;

        Entity vehicle = minecraft.player.getVehicle();
        if (vehicle == null) {
            require(++ridingSyncTicks <= RIDING_SYNC_GRACE_TICKS,
                "client returned to Overworld but riding relation did not synchronize");
            return;
        }

        require(vehicle.getUUID().equals(vehicleId),
            "client mounted a different vehicle after round trip");
        require(vehicle.getPassengers().contains(minecraft.player),
            "client vehicle passenger graph is inconsistent after round trip");
        require(hasSubLevel(Level.OVERWORLD), "returned Sable sublevel missing in Overworld");
        verifyInterpolationHistory(Level.OVERWORLD, "return destination", MIN_GRAFTED_HISTORY_SNAPSHOTS);
        verifyYawDelta(minecraft, 0.0f, "inverse rotated return crossing");

        SableDimensionStackIntegrationMarkers.acknowledge("return");
        phase = Phase.WAIT_FOR_GRAVITY_RECROSS;
        phaseTicks = 0;
    }

    private static void waitForGravityRecross(Minecraft minecraft) {
        if (minecraft.level.dimension().equals(Level.OVERWORLD)) {
            Entity vehicle = minecraft.player.getVehicle();
            require(vehicle != null && vehicle.getUUID().equals(vehicleId),
                "client lost Create seat while gravity was reversing returned body");
            return;
        }
        if (!minecraft.level.dimension().equals(Level.NETHER)) return;

        Entity vehicle = minecraft.player.getVehicle();
        if (vehicle == null) {
            require(++ridingSyncTicks <= RIDING_SYNC_GRACE_TICKS,
                "client reached Nether on gravity recross but Create seat did not synchronize");
            return;
        }
        require(vehicle.getUUID().equals(vehicleId),
            "client mounted a different vehicle after gravity-driven recross");
        require(vehicle.getPassengers().contains(minecraft.player),
            "client Create seat passenger graph is inconsistent after gravity recross");
        require(hasSubLevel(Level.NETHER), "gravity-recrossed Sable sublevel missing in Nether");
        verifyInterpolationHistory(Level.NETHER, "gravity recross destination", MIN_GRAFTED_HISTORY_SNAPSHOTS);
        verifyYawDelta(minecraft, EXPECTED_PORTAL_YAW_DELTA, "gravity-driven rotated recross");
        require(seamTransitions == EXPECTED_SEAM_TRANSITIONS,
            "unexpected client seam-transition count: " + seamTransitions);

        SableDimensionStackIntegrationMarkers.acknowledge("recross");
        // Hold the real input so vanilla's subsequent passenger-input packets also
        // carry sneaking=true. A lone command is overwritten by the next input tick.
        minecraft.options.keyShift.setDown(true);
        ridingSyncTicks = 0;
        phase = Phase.WAIT_FOR_DISMOUNT;
        phaseTicks = 0;
    }

    private static void waitForDismount(Minecraft minecraft) {
        if (minecraft.player.getVehicle() != null) {
            require(++ridingSyncTicks <= RIDING_SYNC_GRACE_TICKS,
                "client crouch/dismount input did not detach from Create seat");
            return;
        }
        minecraft.options.keyShift.setDown(false);
        ridingSyncTicks = 0;
        require(seamTransitions == EXPECTED_SEAM_TRANSITIONS,
            "dimension flicker occurred before remote-observer setup");
        SableDimensionStackIntegrationMarkers.acknowledge("dismount");
        phase = Phase.WAIT_FOR_REMOTE_SOURCE;
        phaseTicks = 0;
    }

    /**
     * The server intentionally moves only the player back to Overworld. The Sable body remains
     * canonical in Nether and must stay loaded/tracked in the client's remote Nether world.
     */
    private static void waitForRemoteSource(Minecraft minecraft) {
        if (!minecraft.level.dimension().equals(Level.OVERWORLD)) return;
        require(minecraft.player.getVehicle() == null,
            "remote observer unexpectedly remounted a vehicle");

        ClientSubLevel remote = getClientSubLevel(Level.NETHER);
        require(remote != null,
            "remote Nether Sable sublevel disappeared when observer returned to Overworld");
        remoteSourceX = remote.logicalPose().position().x();
        require(Double.isFinite(remoteSourceX),
            "remote Nether Sable sublevel has a non-finite source pose");
        SableDimensionStackIntegrationMarkers.acknowledge("remote-source");
        phase = Phase.WAIT_FOR_REMOTE_MOVEMENT;
        phaseTicks = 0;
    }

    /** Prove live movement packets continue updating the non-current client world. */
    private static void waitForRemoteMovement(Minecraft minecraft) {
        require(minecraft.level.dimension().equals(Level.OVERWORLD),
            "remote observer left Overworld while waiting for Nether movement");
        require(minecraft.player.getVehicle() == null,
            "remote observer remounted while waiting for Nether movement");

        ClientSubLevel remote = getClientSubLevel(Level.NETHER);
        require(remote != null,
            "remote Nether Sable sublevel disappeared while it was moving");
        double currentX = remote.logicalPose().position().x();
        require(Double.isFinite(currentX),
            "remote Nether Sable sublevel produced a non-finite moving pose");
        if (Math.abs(currentX - remoteSourceX) < MIN_REMOTE_OBSERVED_MOVEMENT) return;

        SableDimensionStackIntegrationMarkers.acknowledge("remote-moved");
        phase = Phase.WAIT_FOR_SERVER_PASS;
        phaseTicks = 0;
    }

    private static void waitForStableServerConfirmedRemoteMovement(Minecraft minecraft) {
        if (!SableDimensionStackIntegrationMarkers.exists("server-pass.txt")) return;
        require(minecraft.level.dimension().equals(Level.OVERWORLD),
            "observer was not left in Overworld after remote tracking proof");
        require(minecraft.player.getVehicle() == null,
            "client became mounted again after server-confirmed dismount");
        require(seamTransitions == EXPECTED_SEAM_TRANSITIONS,
            "dimension flicker occurred during the expected three portal crossings");

        ClientSubLevel remote = getClientSubLevel(Level.NETHER);
        require(remote != null,
            "remote Nether Sable sublevel vanished before final client confirmation");
        require(Math.abs(remote.logicalPose().position().x() - remoteSourceX) >= MIN_REMOTE_OBSERVED_MOVEMENT,
            "remote Nether Sable pose regressed before final client confirmation");

        phase = Phase.DONE;
        SableDimensionStackIntegrationMarkers.clientPass(
            "real client verified continuous Sable ownership/interpolation, exact three-crossing sequence, rotated camera/facing handoff, rider tracking/dismount, and opposite-dimension live movement"
        );
        minecraft.stop();
    }

    private static String diagnosticState() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return " clientWorld=not-ready connectionRequested=" + connectionRequested;
        }
        Entity vehicle = minecraft.player.getVehicle();
        ClientSubLevel remote = getClientSubLevel(Level.NETHER);
        return " clientDim=" + minecraft.level.dimension().location()
            + " riding=" + (vehicle == null ? "none" : vehicle.getUUID())
            + " expectedVehicle=" + vehicleId
            + " subLevel=" + subLevelId
            + " seamTransitions=" + seamTransitions
            + " sourceWorldYaw=" + sourceWorldYaw
            + " currentWorldYaw=" + worldYaw(minecraft.player)
            + " rawLocalYaw=" + minecraft.player.getYRot()
            + " remoteNether=" + (remote == null ? "missing" : remote.logicalPose().position());
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }

    private static void fail(String detail, Throwable error) {
        phase = Phase.DONE;
        try {
            SableDimensionStackIntegrationMarkers.clientFail(detail, error);
        }
        finally {
            Minecraft.getInstance().stop();
        }
    }
}
