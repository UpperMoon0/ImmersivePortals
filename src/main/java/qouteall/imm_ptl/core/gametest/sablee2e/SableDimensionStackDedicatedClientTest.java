package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.UUID;

/** Real-client observer for the Sable cross-dimension continuity regression test. */
public final class SableDimensionStackDedicatedClientTest {
    private static final String DEDICATED_ADDRESS = "127.0.0.1:" + System.getenv().getOrDefault("IP_SABLE_E2E_PORT", "25565");
    private static final int TIMEOUT_TICKS = 1200;
    private static final int RIDING_SYNC_GRACE_TICKS = 120;
    private static final int RETURN_STABLE_TICKS = 10;
    private static final int MAX_OVERLAP_TICKS = 20;
    private static final int EXPECTED_DIMENSION_TRANSITIONS = 3;

    private enum Phase {
        CONNECT,
        WAIT_FOR_SOURCE_RIDE,
        WAIT_FOR_DESTINATION_RIDE,
        WAIT_FOR_RETURN_RIDE,
        WAIT_FOR_GRAVITY_RECROSS,
        WAIT_FOR_DISMOUNT,
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
    private static int stableReturnTicks;
    private static int overlapTicks;
    private static int dimensionTransitions;
    private static ResourceKey<Level> lastObservedDimension;

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

            if (subLevelId != null) {
                verifyContinuousClientOwnership(minecraft);
            }

            switch (phase) {
                case WAIT_FOR_SOURCE_RIDE -> waitForSourceRide(minecraft);
                case WAIT_FOR_DESTINATION_RIDE -> waitForDestinationRide(minecraft);
                case WAIT_FOR_RETURN_RIDE -> waitForReturnRide(minecraft);
                case WAIT_FOR_GRAVITY_RECROSS -> waitForGravityRecross(minecraft);
                case WAIT_FOR_DISMOUNT -> waitForDismount(minecraft);
                case WAIT_FOR_SERVER_PASS -> waitForStableServerConfirmedDismount(minecraft);
                case CONNECT, DONE -> { }
            }
        }
        catch (Throwable error) {
            fail("exception phase=" + phase + diagnosticState(), error);
        }
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
        lastObservedDimension = minecraft.level.dimension();
        overlapTicks = 0;
        dimensionTransitions = 0;
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
            require(hasSubLevel(currentDimension),
                "current client dimension changed before destination Sable sublevel was synchronized");
        }

        if (inOverworld && inNether) {
            require(++overlapTicks <= MAX_OVERLAP_TICKS,
                "source and destination Sable client copies overlapped too long");
        }
        else {
            overlapTicks = 0;
        }

        if (lastObservedDimension != null && !lastObservedDimension.equals(currentDimension)) {
            dimensionTransitions++;
            require(dimensionTransitions <= EXPECTED_DIMENSION_TRANSITIONS,
                "client dimension ownership flickered/ping-ponged across the portal seam");
        }
        lastObservedDimension = currentDimension;
    }

    private static boolean hasSubLevel(ResourceKey<Level> dimension) {
        if (subLevelId == null) return false;
        ClientLevel world = ClientWorldLoader.getWorld(dimension);
        SubLevelContainer container = SubLevelContainer.getContainer(world);
        return container != null && container.getSubLevel(subLevelId) != null;
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

        SableDimensionStackIntegrationMarkers.acknowledge("return");
        stableReturnTicks = 0;
        phase = Phase.WAIT_FOR_GRAVITY_RECROSS;
        phaseTicks = 0;
    }

    private static void waitForGravityRecross(Minecraft minecraft) {
        if (minecraft.level.dimension().equals(Level.OVERWORLD)) {
            Entity vehicle = minecraft.player.getVehicle();
            require(vehicle != null && vehicle.getUUID().equals(vehicleId),
                "client lost Create seat while gravity was reversing returned body");
            if (stableReturnTicks < RETURN_STABLE_TICKS) stableReturnTicks++;
            return;
        }
        if (!minecraft.level.dimension().equals(Level.NETHER)) return;

        require(stableReturnTicks >= RETURN_STABLE_TICKS,
            "body recrossed before client observed a stable returned Overworld state");
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
        require(dimensionTransitions == EXPECTED_DIMENSION_TRANSITIONS,
            "unexpected client dimension transition count: " + dimensionTransitions);

        SableDimensionStackIntegrationMarkers.acknowledge("recross");
        minecraft.getConnection().send(new ServerboundPlayerCommandPacket(
            minecraft.player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
        ));
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
        ridingSyncTicks = 0;
        SableDimensionStackIntegrationMarkers.acknowledge("dismount");
        phase = Phase.WAIT_FOR_SERVER_PASS;
        phaseTicks = 0;
    }

    private static void waitForStableServerConfirmedDismount(Minecraft minecraft) {
        if (!SableDimensionStackIntegrationMarkers.exists("server-pass.txt")) return;
        require(minecraft.player.getVehicle() == null,
            "client became mounted again after server-confirmed dismount");
        require(dimensionTransitions == EXPECTED_DIMENSION_TRANSITIONS,
            "dimension flicker occurred after the expected crossing sequence");
        phase = Phase.DONE;
        SableDimensionStackIntegrationMarkers.clientPass(
            "real client verified continuous Sable ownership, exact three-crossing sequence, rider tracking, gravity recross, and dismount"
        );
        minecraft.stop();
    }

    private static String diagnosticState() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return " clientWorld=not-ready connectionRequested=" + connectionRequested;
        }
        Entity vehicle = minecraft.player.getVehicle();
        return " clientDim=" + minecraft.level.dimension().location()
            + " riding=" + (vehicle == null ? "none" : vehicle.getUUID())
            + " expectedVehicle=" + vehicleId
            + " subLevel=" + subLevelId
            + " transitions=" + dimensionTransitions;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }

    private static void fail(String detail, Throwable error) {
        phase = Phase.DONE;
        try {
            SableDimensionStackIntegrationMarkers.clientFail(detail, error);
        } finally {
            Minecraft.getInstance().stop();
        }
    }
}
