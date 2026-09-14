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

import java.util.UUID;

/** Client state for one server-first Sable rider handoff. */
public final class SableServerFirstClientHandoff {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Pending pending;

    static {
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> clear());
    }

    private SableServerFirstClientHandoff() {}

    /** Begin a client-detected handoff and return the nonce the server must echo. */
    public static UUID begin(Portal portal) {
        UUID handoffId = UUID.randomUUID();
        pending = new Pending(handoffId, portal.getUUID(), null);
        return handoffId;
    }

    /**
     * A physics-first server migration must announce this before its authoritative dimension
     * packet. Replacing a still-pending client request is intentional: whichever ordering wins on
     * the server establishes the single handoff id whose Ack may apply the camera transform.
     */
    public static void prepareServerInitiated(
        UUID handoffId,
        UUID portalId,
        ResourceKey<Level> destinationDimension,
        @Nullable DQuaternion rotation,
        boolean teleportChangesGravity
    ) {
        pending = new Pending(
            handoffId,
            portalId,
            new PreparedTransform(destinationDimension, rotation, teleportChangesGravity)
        );
    }

    public static boolean hasServerInitiatedHandoff(UUID portalId) {
        Pending current = pending;
        return current != null
            && current.serverTransform() != null
            && current.portalId().equals(portalId);
    }

    /**
     * Apply the normal IP camera/gravity transform only for the one handoff id currently pending.
     * Server-initiated Acks are accepted only if their Prepare packet established the same id
     * before the dimension switch; delayed client-request Acks and duplicate Acks are ignored.
     */
    public static void acknowledge(
        UUID handoffId,
        UUID portalId,
        boolean serverInitiated,
        boolean success,
        ResourceKey<Level> ackDestinationDimension,
        @Nullable DQuaternion ackRotation,
        boolean ackTeleportChangesGravity
    ) {
        Pending expected = pending;
        if (expected == null
            || !expected.handoffId().equals(handoffId)
            || !expected.portalId().equals(portalId)
            || (expected.serverTransform() != null) != serverInitiated) {
            return;
        }
        pending = null;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        if (!success) {
            clearClientPendingGate(player);
            return;
        }

        PreparedTransform prepared = expected.serverTransform();
        ResourceKey<Level> destinationDimension = prepared != null
            ? prepared.destinationDimension()
            : ackDestinationDimension;
        DQuaternion rotation = prepared != null ? prepared.rotation() : ackRotation;
        boolean teleportChangesGravity = prepared != null
            ? prepared.teleportChangesGravity()
            : ackTeleportChangesGravity;

        if (!player.level().dimension().equals(destinationDimension)) {
            LOGGER.error(
                "Sable server-first teleport {} for portal {} was acknowledged before the authoritative dimension switch (expected {}, got {})",
                handoffId, portalId, destinationDimension.location(), player.level().dimension().location()
            );
            clearClientPendingGate(player);
            return;
        }

        // Sable cross-dimension migration rejects scaled portals, so the prepared/server-echoed
        // transform snapshot is sufficient to run the exact normal IP camera/gravity path without
        // relying on the source portal entity still existing in the client's source world.
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
    }

    private static void clearClientPendingGate(LocalPlayer player) {
        ClientTeleportationManager.forceTeleportPlayer(
            player.level().dimension(), player.position()
        );
    }

    private record Pending(
        UUID handoffId,
        UUID portalId,
        @Nullable PreparedTransform serverTransform
    ) {}

    private record PreparedTransform(
        ResourceKey<Level> destinationDimension,
        @Nullable DQuaternion rotation,
        boolean teleportChangesGravity
    ) {}
}
