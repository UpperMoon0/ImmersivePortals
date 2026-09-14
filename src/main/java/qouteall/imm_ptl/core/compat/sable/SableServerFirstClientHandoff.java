package qouteall.imm_ptl.core.compat.sable;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
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
    private static final int RIDER_SYNC_TIMEOUT_TICKS = 100;
    private static final int POST_HANDOFF_TELEPORT_GUARD_TICKS = 2;
    private static Pending pending;
    private static ReadyToApply readyToApply;

    static {
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> clear());
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class, event -> tick());
    }

    private SableServerFirstClientHandoff() {}

    /** Begin a client-detected handoff and return the nonce the server must echo. */
    public static UUID begin(Portal portal) {
        UUID handoffId = UUID.randomUUID();
        LocalPlayer player = Minecraft.getInstance().player;
        PreparedTransform interpolationTransform = new PreparedTransform(
            portal.getDestDim(), portal.getRotation(), portal.getTeleportChangesGravity()
        );
        pending = new Pending(handoffId, portal.getUUID(), null, interpolationTransform,
            TransformationManager.capturePlayerRotationContext(), player.getXRot(), player.getYRot());
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
        if (Minecraft.getInstance().player == null) {
            clear();
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        PreparedTransform transform = new PreparedTransform(
            destinationDimension, rotation, teleportChangesGravity
        );
        pending = new Pending(
            handoffId,
            portalId,
            transform,
            transform,
            TransformationManager.capturePlayerRotationContext(),
            player.getXRot(),
            player.getYRot()
        );
    }

    /**
     * Return the exact portal rotation for a destination full-sync belonging to the active rider
     * handoff. TCP snapshots can lag the migration tick; inferring this from mismatched source and
     * destination snapshots would fold body motion into the portal transform.
     */
    public static @Nullable DQuaternion getActiveInterpolationRotation(
        ResourceKey<Level> destinationDimension
    ) {
        Pending current = pending;
        if (current != null
            && current.interpolationTransform().destinationDimension().equals(destinationDimension)) {
            return current.interpolationTransform().rotation();
        }
        ReadyToApply ready = readyToApply;
        if (ready != null && ready.destinationDimension().equals(destinationDimension)) {
            return ready.rotation();
        }
        return null;
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
            readyToApply = null;
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

        // A retained Sable rider stores yaw/pitch in the sublevel's local frame. The destination
        // sublevel itself already contains the portal rotation. Applying the normal IP raw-yaw
        // transform before Sable's delayed passenger attachment therefore rotates the camera twice.
        // Wait for the destination seat relation, then restore the pre-packet local look exactly.
        readyToApply = new ReadyToApply(
            handoffId, destinationDimension, rotation, teleportChangesGravity,
            expected.rotationContext(), expected.localPitch(), expected.localYaw(), 0
        );
        tryApplyReady(player);
    }

    private static void tick() {
        ReadyToApply ready = readyToApply;
        LocalPlayer player = Minecraft.getInstance().player;
        if (ready == null || player == null) return;

        if (tryApplyReady(player)) return;

        int waitTicks = ready.waitTicks() + 1;
        if (waitTicks <= RIDER_SYNC_TIMEOUT_TICKS) {
            readyToApply = ready.withWaitTicks(waitTicks);
            return;
        }

        // Do not leave camera/gravity state pending forever if the riding graph failed to arrive.
        // The E2E rider relation assertion will still fail independently; this fallback only keeps
        // the client usable and mirrors normal IP behavior for an unexpectedly unmounted player.
        LOGGER.warn("Timed out waiting for destination Sable rider relation for handoff {}; applying normal portal camera transform",
            ready.handoffId());
        applyFallbackTransform(player, ready);
        readyToApply = null;
    }

    private static boolean tryApplyReady(LocalPlayer player) {
        ReadyToApply ready = readyToApply;
        if (ready == null || !player.level().dimension().equals(ready.destinationDimension())) return false;

        if (!isAttachedToCurrentSableSubLevel(player)) return false;

        Portal transformSnapshot = createTransformSnapshot(player, ready);
        Vec3 oldRealVelocity = McHelper.getWorldVelocity(player);
        TransformationManager.changePlayerGravity(
            transformSnapshot, ready.rotationContext().baseGravityDirection()
        );
        TransformationManager.setPlayerRawRotation(player, ready.localPitch(), ready.localYaw());
        McHelper.setWorldVelocity(player, oldRealVelocity);
        ScaleUtilsClient.onClientPlayerTeleported(transformSnapshot);
        guardAgainstImmediatePortalRetrigger();
        readyToApply = null;
        return true;
    }

    private static boolean isAttachedToCurrentSableSubLevel(LocalPlayer player) {
        if (player.getVehicle() == null) return false;
        SubLevel subLevel = Sable.HELPER.getContaining(player.getVehicle());
        return subLevel != null && subLevel.getLevel() == player.level();
    }

    private static void applyFallbackTransform(LocalPlayer player, ReadyToApply ready) {
        Portal transformSnapshot = createTransformSnapshot(player, ready);
        Vec3 oldRealVelocity = McHelper.getWorldVelocity(player);
        TransformationManager.managePlayerRotationAndChangeGravity(
            transformSnapshot, ready.rotationContext()
        );
        McHelper.setWorldVelocity(player, oldRealVelocity);
        ScaleUtilsClient.onClientPlayerTeleported(transformSnapshot);
        guardAgainstImmediatePortalRetrigger();
    }


    /**
     * Sable projects a dismounting rider from hidden sublevel-local coordinates back into world
     * space. Immediately after a dimension-stack handoff that projection can cross the just-used
     * portal plane in the client interpolation history even though the server rider never crossed
     * again. Ignore only normal client-predicted portal teleports for two ticks so the position
     * history can settle; retained Sable riders still use the server-first path before this guard.
     */
    private static void guardAgainstImmediatePortalRetrigger() {
        ClientTeleportationManager.disableTeleportFor(POST_HANDOFF_TELEPORT_GUARD_TICKS);
    }
    private static Portal createTransformSnapshot(LocalPlayer player, ReadyToApply ready) {
        Portal transformSnapshot = new Portal(Portal.ENTITY_TYPE, player.level());
        transformSnapshot.setDestinationDimension(ready.destinationDimension());
        transformSnapshot.setRotation(ready.rotation());
        transformSnapshot.setTeleportChangesGravity(ready.teleportChangesGravity());
        return transformSnapshot;
    }

    public static void clear() {
        pending = null;
        readyToApply = null;
    }

    private static void clearClientPendingGate(LocalPlayer player) {
        ClientTeleportationManager.forceTeleportPlayer(
            player.level().dimension(), player.position()
        );
    }

    private record Pending(
        UUID handoffId,
        UUID portalId,
        @Nullable PreparedTransform serverTransform,
        PreparedTransform interpolationTransform,
        TransformationManager.PlayerRotationContext rotationContext,
        float localPitch,
        float localYaw
    ) {}

    private record ReadyToApply(
        UUID handoffId,
        ResourceKey<Level> destinationDimension,
        @Nullable DQuaternion rotation,
        boolean teleportChangesGravity,
        TransformationManager.PlayerRotationContext rotationContext,
        float localPitch,
        float localYaw,
        int waitTicks
    ) {
        ReadyToApply withWaitTicks(int ticks) {
            return new ReadyToApply(
                handoffId, destinationDimension, rotation, teleportChangesGravity,
                rotationContext, localPitch, localYaw, ticks
            );
        }
    }

    private record PreparedTransform(
        ResourceKey<Level> destinationDimension,
        @Nullable DQuaternion rotation,
        boolean teleportChangesGravity
    ) {}
}
