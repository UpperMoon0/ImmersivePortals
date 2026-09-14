package qouteall.imm_ptl.core.compat.sable;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ScaleUtilsClient;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

import java.util.UUID;

/** Client state for one server-first Sable rider handoff. */
public final class SableServerFirstClientHandoff {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Portal pendingPortal;

    static {
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> clear());
    }

    private SableServerFirstClientHandoff() {}

    public static void begin(Portal portal) {
        pendingPortal = portal;
    }

    /**
     * The server sends its authoritative position packet before this acknowledgement. On success,
     * apply the same camera/gravity transform that normal client-predicted portal teleportation
     * performs. Clearing the saved portal before applying it makes duplicate acknowledgements
     * harmless and guarantees the transform runs at most once.
     */
    public static void acknowledge(UUID portalId, boolean success) {
        Portal portal = pendingPortal;
        if (portal == null || !portal.getUUID().equals(portalId)) return;
        pendingPortal = null;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) return;

        if (!success) {
            clearClientPendingGate(player);
            return;
        }

        if (!player.level().dimension().equals(portal.getDestDim())) {
            LOGGER.error(
                "Sable server-first teleport {} was acknowledged before the authoritative dimension switch",
                portalId
            );
            clearClientPendingGate(player);
            return;
        }

        Vec3 oldRealVelocity = McHelper.getWorldVelocity(player);
        TransformationManager.managePlayerRotationAndChangeGravity(portal);
        McHelper.setWorldVelocity(player, oldRealVelocity);
        ScaleUtilsClient.onClientPlayerTeleported(portal);
    }

    public static void clear() {
        pendingPortal = null;
    }

    private static void clearClientPendingGate(LocalPlayer player) {
        ClientTeleportationManager.forceTeleportPlayer(
            player.level().dimension(), player.position()
        );
    }
}
