package qouteall.imm_ptl.core.gametest.sablee2e;

import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable.SableServerFirstClientHandoff;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;
import java.util.UUID;

/** Calls the production handoff entry points on actual Minecraft objects, never a policy mock. */
final class SableRiderLifecycleProbe {
    private SableRiderLifecycleProbe() {}

    static void beforeCrossings(Minecraft minecraft, Portal portal, Entity seat) {
        String expectedNetworking = System.getenv().getOrDefault("IP_VERIFICATION_NETWORKING", "bundled");
        if (expectedNetworking.equals("bundled")) expectedNetworking = "4.2.2+a92978fd19";
        String installedNetworking = net.neoforged.fml.ModList.get().getModContainerById("fabric_networking_api_v1")
            .orElseThrow().getModInfo().getVersion().toString();
        require(installedNetworking.equals(expectedNetworking), "Native lane loaded " + installedNetworking
            + " instead of requested networking API " + expectedNetworking);
        System.out.println("[Immersive Portals Sable E2E] selected networking API=" + installedNetworking);
        SableDimensionStackIntegrationMarkers.acknowledge("networking-api");
        LocalPlayer player = minecraft.player;
        var level = player.level();
        float health = player.getHealth();
        try {
            UUID id = beginDetached(player, portal, seat);
            SableServerFirstClientHandoff.acknowledge(UUID.randomUUID(), portal.getUUID(), false, false,
                portal.getDestDim(), null, false);
            require(SableServerFirstClientHandoff.getActiveHandoffSnapshot() != null, "Unrelated Ack consumed pending handoff");
            SableServerFirstClientHandoff.acknowledge(id, portal.getUUID(), false, false, portal.getDestDim(), null, false);
            released(player, "Rejected Ack");
            SableServerFirstClientHandoff.acknowledge(id, portal.getUUID(), false, true, portal.getDestDim(), null, false);
            released(player, "Duplicate terminal Ack");

            beginDetached(player, portal, seat);
            SableServerFirstClientHandoff.clear();
            released(player, "Explicit cancellation");

            beginDetached(player, portal, seat);
            LocalPlayer replacement = new LocalPlayer(minecraft, minecraft.level, player.connection,
                player.getStats(), player.getRecipeBook(), false, false);
            require(replacement.getUUID().equals(player.getUUID()), "Replacement fixture changed UUID");
            replacement.setPos(player.position());
            minecraft.player = replacement;
            try {
                require(!SableServerFirstClientHandoff.shouldHoldDetachedMovement(replacement), "Respawn inherited movement hold");
            } finally {
                minecraft.player = player;
            }
            released(player, "Replacement player");

            beginDetached(player, portal, seat);
            player.setHealth(0);
            released(player, "Dead player");
            player.setHealth(health);

            beginDetached(player, portal, seat);
            Entity otherSeat = EntityType.ARMOR_STAND.create(minecraft.level);
            require(otherSeat != null && player.startRiding(otherSeat, true), "Unrelated seat fixture failed");
            released(player, "Unrelated seat");
            player.stopRiding();
            released(player, "Dismount after unrelated seat");

            beginDetached(player, portal, seat);
            var thirdWorld = ClientWorldLoader.getWorld(Level.END);
            minecraft.level = thirdWorld;
            ((IEEntity) player).ip_setWorld(thirdWorld);
            try {
                // Keep the client/player frames consistent so releasing portal gating cannot
                // trigger an actual conventional teleport during this isolated lifecycle probe.
                require(!SableServerFirstClientHandoff.shouldHoldDetachedMovement(player), "Third dimension retained movement hold");
            } finally {
                minecraft.level = (net.minecraft.client.multiplayer.ClientLevel) level;
                ((IEEntity) player).ip_setWorld(level);
            }
            released(player, "Source-third-source teleport");

            beginDetached(player, portal, seat);
            require(player.startRiding(seat, true), "Remount fixture failed");
            require(!SableServerFirstClientHandoff.shouldHoldDetachedMovement(player), "Remount retained movement hold");
            player.stopRiding();
            require(!SableServerFirstClientHandoff.shouldHoldDetachedMovement(player), "Later dismount rearmed movement hold");
            require(SableServerFirstClientHandoff.getActiveHandoffSnapshot() != null, "Remount lost pending-Ack watchdog");
            SableServerFirstClientHandoff.clear();

            SableDimensionStackIntegrationMarkers.acknowledge("movement-lifecycle");
        } finally {
            minecraft.player = player;
            minecraft.level = (net.minecraft.client.multiplayer.ClientLevel) level;
            ((IEEntity) player).ip_setWorld(level);
            player.setHealth(health);
            SableServerFirstClientHandoff.clear();
            require(player.getVehicle() == seat || player.startRiding(seat, true), "Cannot restore lifecycle probe seat");
        }
    }

    static void cleanupAndDisconnect(Minecraft minecraft, Portal portal, Entity seat) {
        LocalPlayer player = minecraft.player;
        // Match the leave-world lifecycle: close the connection before clearing worlds or
        // registries, so ordinary in-flight packets cannot decode against torn-down state.
        var connection = minecraft.getConnection().getConnection();
        SableDimensionStackIntegrationMarkers.acknowledge("movement-disconnect-started");
        connection.disconnect(net.minecraft.network.chat.Component.translatable("multiplayer.status.quitting"));
        require(!connection.isConnected(), "Native connection remained open before cleanup");
        // ClientCleanupEvent is the production hook used by conventional travel and disconnect.
        beginDetached(player, portal, seat);
        NeoForge.EVENT_BUS.post(new ClientCleanupEvent());
        released(player, "Client cleanup event");
        beginDetached(player, portal, seat);
        minecraft.disconnect();
        require(minecraft.player == null, "Native disconnect retained local player");
        require(SableServerFirstClientHandoff.getActiveHandoffSnapshot() == null, "Native disconnect retained handoff");
        require(!SableServerFirstClientHandoff.shouldHoldDetachedMovement(player), "Native disconnect retained movement hold");
        SableDimensionStackIntegrationMarkers.acknowledge("movement-disconnect");
    }

    private static UUID beginDetached(LocalPlayer player, Portal portal, Entity seat) {
        require(player.getVehicle() == seat || player.startRiding(seat, true), "Cannot mount lifecycle probe seat");
        UUID id = SableServerFirstClientHandoff.begin(portal);
        player.stopRiding();
        var position = player.position();
        var velocity = player.getDeltaMovement();
        boolean flying = player.getAbilities().flying, noGravity = player.isNoGravity();
        require(SableServerFirstClientHandoff.shouldHoldDetachedMovement(player), "Lifecycle probe did not arm");
        require(player.position().equals(position) && player.getDeltaMovement().equals(velocity)
            && player.getAbilities().flying == flying && player.isNoGravity() == noGravity,
            "Movement hold overwrote position, velocity or flight/gravity flags");
        return id;
    }

    private static void released(LocalPlayer player, String scenario) {
        require(!SableServerFirstClientHandoff.shouldHoldDetachedMovement(player), scenario + " retained movement hold");
        require(SableServerFirstClientHandoff.getActiveHandoffSnapshot() == null, scenario + " retained protocol gate");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
