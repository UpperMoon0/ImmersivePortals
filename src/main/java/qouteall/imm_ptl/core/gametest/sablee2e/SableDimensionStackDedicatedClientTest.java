package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.UUID;

/** Real-client observer for the Sable dimension-stack dedicated integration test. */
@EventBusSubscriber(modid = qouteall.imm_ptl.core.platform_specific.IPModEntry.MODID, value = Dist.CLIENT)
public final class SableDimensionStackDedicatedClientTest {
    private static final String DEDICATED_ADDRESS = "127.0.0.1:" + System.getenv().getOrDefault("IP_SABLE_E2E_PORT", "25565");
    private static final int TIMEOUT_TICKS = 1200;
    private static final int RIDING_SYNC_GRACE_TICKS = 120;
    private static final int RETURN_STABLE_TICKS = 10;

    private enum Phase {
        CONNECT,
        WAIT_FOR_SOURCE_RIDE,
        WAIT_FOR_DESTINATION_RIDE,
        WAIT_FOR_RETURN_RIDE,
        WAIT_FOR_SERVER_PASS,
        DONE
    }

    private static Phase phase = Phase.CONNECT;
    private static int ticks;
    private static int phaseTicks;
    private static boolean connectionRequested;
    private static UUID vehicleId;
    private static int ridingSyncTicks;
    private static int stableReturnTicks;

    private SableDimensionStackDedicatedClientTest() {}

    @SubscribeEvent
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

            switch (phase) {
                case WAIT_FOR_SOURCE_RIDE -> waitForSourceRide(minecraft);
                case WAIT_FOR_DESTINATION_RIDE -> waitForDestinationRide(minecraft);
                case WAIT_FOR_RETURN_RIDE -> waitForReturnRide(minecraft);
                case WAIT_FOR_SERVER_PASS -> waitForStableServerConfirmedReturn(minecraft);
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
        SableDimensionStackIntegrationMarkers.acknowledge("source");
        phase = Phase.WAIT_FOR_DESTINATION_RIDE;
        phaseTicks = 0;
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

        SableDimensionStackIntegrationMarkers.acknowledge("return");
        phase = Phase.WAIT_FOR_SERVER_PASS;
        phaseTicks = 0;
    }

    private static void waitForStableServerConfirmedReturn(Minecraft minecraft) {
        if (!SableDimensionStackIntegrationMarkers.exists("server-pass.txt")) return;

        Entity vehicle = minecraft.player.getVehicle();
        boolean stable = minecraft.level.dimension().equals(Level.OVERWORLD)
            && vehicle != null
            && vehicle.getUUID().equals(vehicleId)
            && vehicle.getPassengers().contains(minecraft.player);

        if (!stable) {
            stableReturnTicks = 0;
            require(++ridingSyncTicks <= RIDING_SYNC_GRACE_TICKS,
                "client did not settle in returned dimension with the original riding graph after server pass");
            return;
        }

        ridingSyncTicks = 0;
        if (++stableReturnTicks < RETURN_STABLE_TICKS) return;

        phase = Phase.DONE;
        SableDimensionStackIntegrationMarkers.clientPass(
            "real client verified source, destination, and stable return riding graph with the same vehicle UUID");
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
            + " expectedVehicle=" + vehicleId;
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
