package qouteall.imm_ptl.core.gametest.sablee2e;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import qouteall.imm_ptl.core.compat.sable.SableInterpolationHistoryDiagnostics;
import qouteall.imm_ptl.core.platform_specific.IPModEntry;

/** Keep optional Sable types out of auto-subscribers' reflected method signatures. */
public final class SableE2EEvents {
    private static boolean enabled() {
        return SableDimensionStackIntegrationMarkers.enabled() && ModList.get().isLoaded("sable");
    }

    @EventBusSubscriber(modid = IPModEntry.MODID)
    public static final class Server {
        @SubscribeEvent
        public static void login(PlayerEvent.PlayerLoggedInEvent event) {
            if (enabled()) {
                if (SableStandingBalloonServer.enabled()) SableStandingBalloonServer.login(event);
                else if (SableShipFlightTest.enabled()) SableShipFlightTest.onPlayerLogin(event);
                else SableDimensionStackDedicatedServerTest.onPlayerLogin(event);
            }
        }
        @SubscribeEvent
        public static void tick(ServerTickEvent.Post event) {
            if (enabled()) {
                if (SableStandingBalloonServer.enabled()) SableStandingBalloonServer.tick(event);
                else if (SableShipFlightTest.enabled()) SableShipFlightTest.onServerTick(event);
                else SableDimensionStackDedicatedServerTest.onServerTick(event);
            }
        }
    }

    @EventBusSubscriber(modid = IPModEntry.MODID, value = Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            if (enabled()) {
                // Both drivers can connect on their first tick. Install the test-only
                // oracle before selecting either lane, including complex ship flights.
                SableInterpolationHistoryDiagnostics.setObserver(SableInterpolationHistoryProbe::verify);
                if (SableStandingBalloonServer.enabled()) SableStandingBalloonClient.tick(event);
                else if (SableShipFlightTest.enabled()) SableShipFlightClientTest.onClientTick(event);
                else SableDimensionStackDedicatedClientTest.onClientTick(event);
            }
        }
    }
}
