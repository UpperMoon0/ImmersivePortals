package qouteall.imm_ptl.core.compat.sable;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ScaleUtilsClient;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.LinkedHashSet;
import java.util.UUID;

/** Client state for one server-first Sable rider handoff. */
public final class SableServerFirstClientHandoff {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COMPLETED_SERVER_HANDOFF_LIMIT = 32;
    private static final LinkedHashSet<UUID> completedServerHandoffs = new LinkedHashSet<>();
    private static Pending pending;

    static {
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> clear());
    }

    private SableServerFirstClientHandoff() {}

    /** Begin a client-detected handoff and return the nonce the server must echo. */
    public static UUID begin(Portal portal) {
        UUID handoffId = UUID.randomUUID();
        pending = new Pending(handoffId, portal.getUUID());
        return handoffId;
    }

    /**
     * Apply the authoritative portal transform after the server's cross-dimension position packet.
     * A server-initiated migration supersedes a pending client request for the same portal. The
     * handoff nonce makes delayed/duplicate acknowledgements harmless even when the same global
     * portal is crossed again later.
     */
    public static void acknowledge(
        UUID handoffId,
        UUID portalId,
        boolean serverInitiated,
        boolean success,
        ResourceKey<Level> destinationDimension,
        @Nullable DQuaternion rotation,
        boolean teleportChangesGravity
    ) {
        if (serverInitiated) {
            if (!markServerHandoffCompleted(handoffId)) return;
            if (pending != null && pending.portalId().equals(portalId)) {
                pending = null;
            }
        }
        else {
            Pending expected = pending;
            if (expected == null
                || !expected.handoffId().equals(handoffId)
                || !expected.portalId().equals(portalId)) {
                return;
            }
            pending = null;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        if (!success) {
            clearClientPendingGate(player);
            return;
        }

        if (!player.level().dimension().equals(destinationDimension)) {
            LOGGER.error(
                "Sable server-first teleport {} for portal {} was acknowledged before the authoritative dimension switch (expected {}, got {})",
                handoffId, portalId, destinationDimension.location(), player.level().dimension().location()
            );
            clearClientPendingGate(player);
            return;
        }

        // Sable cross-dimension migration rejects scaled portals, so a lightweight portal snapshot
        // is sufficient to execute the exact normal IP camera/gravity path without relying on the
        // source portal entity still existing on the client.
        Portal transformSnapshot = new Portal(Portal.ENTITY_TYPE, player.level());
        transformSnapshot.setDestinationDimension(destinationDimension);
        transformSnapshot.setRotation(rotation);
        transformSnapshot.setTeleportChangesGravity(teleportChangesGravity);

        Vec3 oldRealVelocity = McHelper.getWorldVelocity(player);
        TransformationManager.managePlayerRotationAndChangeGravity(transformSnapshot);
        McHelper.setWorldVelocity(player, oldRealVelocity);
        ScaleUtilsClient.onClientPlayerTeleported(transformSnapshot);
    }

    public static void clear() {
        pending = null;
        completedServerHandoffs.clear();
    }

    private static boolean markServerHandoffCompleted(UUID handoffId) {
        if (!completedServerHandoffs.add(handoffId)) return false;
        while (completedServerHandoffs.size() > COMPLETED_SERVER_HANDOFF_LIMIT) {
            UUID oldest = completedServerHandoffs.iterator().next();
            completedServerHandoffs.remove(oldest);
        }
        return true;
    }

    private static void clearClientPendingGate(LocalPlayer player) {
        ClientTeleportationManager.forceTeleportPlayer(
            player.level().dimension(), player.position()
        );
    }

    private record Pending(UUID handoffId, UUID portalId) {}
}
