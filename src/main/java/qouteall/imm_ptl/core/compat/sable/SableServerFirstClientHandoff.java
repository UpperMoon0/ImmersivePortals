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
    private static SableRiderMovementHold movementHold;
    private static StandingRebase standingRebase;
    private static StandingCompletionSnapshot standingCompletion;
    private static long unsuccessfulStandingHandoffs;

    /** Read-only acceptance diagnostics; failure history survives cleanup and later successes. */
    public static @Nullable StandingCompletionSnapshot getStandingCompletionSnapshot() { return standingCompletion; }
    public static long getUnsuccessfulStandingHandoffs() { return unsuccessfulStandingHandoffs; }
    public record StandingCompletionSnapshot(UUID handoffId, UUID supportId,
        ResourceKey<Level> sourceDimension, ResourceKey<Level> destinationDimension) {}
    private static void recordUnsuccessfulStandingHandoff(@Nullable RiderIdentity rider) {
        if (rider != null && rider.supportId() != null) unsuccessfulStandingHandoffs++;
    }

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
            TransformationManager.capturePlayerRotationContext(), player.getXRot(), player.getYRot(), captureRider(player));
        startMovementHold(player, pending.rider(), portal.getDestDim());
        LOGGER.debug("Begin Sable client-request handoff={} portal={} support={} source={} destination={}",
            handoffId, portal.getUUID(), pending.rider().supportId(),
            pending.rider().sourceDimension().location(), portal.getDestDim().location());
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
            player.getYRot(),
            captureRider(player)
        );
        startMovementHold(player, pending.rider(), destinationDimension);
    }

    private static void startMovementHold(LocalPlayer player, RiderIdentity rider, ResourceKey<Level> destination) {
        standingCompletion = null;
        if (movementHold != null) movementHold.cancel();
        standingRebase = null;
        if (rider.supportId() != null) {
            movementHold = SableRiderMovementHold.standing(player, rider.sourceDimension(), destination, RIDER_SYNC_TIMEOUT_TICKS);
            return;
        }
        movementHold = rider.vehicleId() == null ? null : new SableRiderMovementHold(player, rider.vehicleId(),
            rider.sourceDimension(), destination, RIDER_SYNC_TIMEOUT_TICKS);
    }

    public static boolean shouldHoldDetachedMovement(LocalPlayer player) {
        if (movementHold == null) return false;
        RiderIdentity identity = pending != null ? pending.rider() : readyToApply != null ? readyToApply.rider()
            : standingRebase != null ? standingRebase.handoff().rider() : null;
        SubLevel currentSupport = SableStandingPassenger.getSupport(player);
        if (identity != null && identity.supportId() != null && currentSupport != null
            && !identity.supportId().equals(currentSupport.getUniqueId())) {
            clear();
            clearClientPendingGate(player);
            return false;
        }
        boolean hold = movementHold.shouldHold(player,
            player.getVehicle() == null ? null : player.getVehicle().getUUID(), player.level().dimension());
        if (movementHold.invalidated() || !player.isAlive()) {
            // Respawns replace LocalPlayer, whereas IP's correlated dimension transfer retains
            // that instance. An unrelated player/frame/seat must also release portal gating.
            clear();
            clearClientPendingGate(player);
            return false;
        }
        return hold;
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
     * Either initiation order owns prediction through its correlated Ack and standing rebase.
     * The authoritative dimension packet clears IP's portal gate before the Ack; a reverse
     * prediction in that gap must not replace the original nonce or standing-support identity.
     */
    public static boolean hasActiveHandoff() {
        return SableClientHandoffGate.isActive(pending, readyToApply, standingRebase);
    }
    /** Read-only correlated lifecycle state for diagnostics and integration assertions. */
    public static @Nullable HandoffSnapshot getActiveHandoffSnapshot() {
        Pending current = pending;
        if (current != null) {
            return new HandoffSnapshot(current.handoffId(), current.rider().vehicleId(),
                current.rider().sourceDimension(), current.interpolationTransform().destinationDimension(),
                current.serverTransform() != null, "awaiting-authoritative-ack");
        }
        if (standingRebase != null) {
            ReadyToApply standing = standingRebase.handoff();
            return new HandoffSnapshot(standing.handoffId(), null, standing.rider().sourceDimension(),
                standing.destinationDimension(), standing.serverInitiated(), "awaiting-standing-movement");
        }
        ReadyToApply ready = readyToApply;
        return ready == null ? null : new HandoffSnapshot(ready.handoffId(), ready.rider().vehicleId(),
            ready.rider().sourceDimension(), ready.destinationDimension(), ready.serverInitiated(),
            "awaiting-destination-seat");
    }

    private static RiderIdentity captureRider(LocalPlayer player) {
        SubLevel support = SableStandingPassenger.getSupport(player);
        return new RiderIdentity(player.getVehicle() == null ? null : player.getVehicle().getUUID(),
            player.level().dimension(), support == null ? null : support.getUniqueId(),
            support == null ? null : support.logicalPose().transformPositionInverse(player.position()));
    }

    public record HandoffSnapshot(
        UUID handoffId, @Nullable UUID vehicleId, ResourceKey<Level> sourceDimension,
        ResourceKey<Level> destinationDimension, boolean serverInitiated, String phase
    ) {}

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
            LOGGER.debug("Ignore unmatched Sable Ack handoff={} portal={} serverInitiated={} active={}",
                handoffId, portalId, serverInitiated, getActiveHandoffSnapshot());
            return;
        }
        LOGGER.debug("Matched Sable Ack handoff={} portal={} serverInitiated={} success={} support={}",
            handoffId, portalId, serverInitiated, success, expected.rider().supportId());
        pending = null;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            recordUnsuccessfulStandingHandoff(expected.rider());
            return;
        }

        if (!success) {
            recordUnsuccessfulStandingHandoff(expected.rider());
            clearMovementHold();
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
            recordUnsuccessfulStandingHandoff(expected.rider());
            LOGGER.error(
                "Sable server-first teleport {} for portal {} was acknowledged before the authoritative dimension switch (expected {}, got {})",
                handoffId, portalId, destinationDimension.location(), player.level().dimension().location()
            );
            clearMovementHold();
            clearClientPendingGate(player);
            return;
        }

        // A retained Sable rider stores yaw/pitch in the sublevel's local frame. The destination
        // sublevel itself already contains the portal rotation. Applying the normal IP raw-yaw
        // transform before Sable's delayed passenger attachment therefore rotates the camera twice.
        // Wait for the destination seat relation, then restore the pre-packet local look exactly.
        readyToApply = new ReadyToApply(
            handoffId, destinationDimension, rotation, teleportChangesGravity,
            expected.rotationContext(), expected.localPitch(), expected.localYaw(), expected.rider(), serverInitiated, 0
        );
        tryApplyReady(player);
    }

    private static void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            clear();
            return;
        }
        // Mounted players need lifecycle observation too: aiStep may not execute while riding.
        shouldHoldDetachedMovement(player);
        if (movementHold != null) {
            movementHold.tick();
            if (movementHold.expired()) {
                // Lost/rejected packet sequences must not leave movement or portal selection
                // locked forever. This is the existing 100-tick rider synchronization limit.
                recordUnsuccessfulStandingHandoff(pending != null ? pending.rider()
                    : readyToApply != null ? readyToApply.rider()
                    : standingRebase != null ? standingRebase.handoff().rider() : null);
                LOGGER.warn("Timed out waiting for Sable rider handoff; releasing local movement hold");
                clearMovementHold();
                if (pending != null) {
                    pending = null;
                    clearClientPendingGate(player);
                }
                // Preserve the existing post-Ack seat timer and fallback transform;
                // ending the movement hold must not discard an acknowledged rotation.
            }
        }
        ReadyToApply ready = readyToApply;
        if (ready == null) return;

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
        recordUnsuccessfulStandingHandoff(ready.rider());
        applyFallbackTransform(player, ready);
        readyToApply = null;
    }

    private static boolean tryApplyReady(LocalPlayer player) {
        ReadyToApply ready = readyToApply;
        if (ready == null || !player.level().dimension().equals(ready.destinationDimension())) return false;

        if (ready.rider().supportId() != null) {
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(player.level());
            SubLevel support = container == null ? null : container.getSubLevel(ready.rider().supportId());
            if (support == null || support.isRemoved()) return false;
            ((dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension) player)
                .sable$setTrackingSubLevel(support);
            // Sable's next native travel step inherits lastPose -> logicalPose. Rebase
            // at that step's entry, into lastPose, so body motion is applied exactly once.
            // Doing this in the packet handler races the next interpolation-clock update.
            standingRebase = new StandingRebase(player, ready);
            applyFallbackTransform(player, ready);
            readyToApply = null;
            return true;
        }
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
        clearMovementHold();
        return true;
    }

    /** Called at aiStep HEAD after native body interpolation and before native inherited motion. */
    public static void prepareStandingMovement(LocalPlayer player) {
        StandingRebase rebase = standingRebase;
        if (rebase == null) return;
        ReadyToApply ready = rebase.handoff();
        if (rebase.player() != player || !ready.destinationDimension().equals(player.level().dimension())
            || !player.isAlive() || player.getVehicle() != null) {
            clear();
            clearClientPendingGate(player);
            return;
        }
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(player.level());
        SubLevel support = container == null ? null : container.getSubLevel(ready.rider().supportId());
        if (support == null || support.isRemoved()) return;
        ((dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension) player)
            .sable$setTrackingSubLevel(support);
        if (ready.rider().supportPosition() != null) {
            Vec3 position = support.lastPose().transformPosition(ready.rider().supportPosition());
            McHelper.setPosAndLastTickPos(player, position, position);
            McHelper.updateBoundingBox(player);
            standingCompletion = new StandingCompletionSnapshot(
                ready.handoffId(), ready.rider().supportId(), ready.rider().sourceDimension(),
                ready.destinationDimension());
        }
        standingRebase = null;
        clearMovementHold();
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

    private static void clearMovementHold() {
        standingRebase = null;
        if (movementHold != null) movementHold.cancel();
        movementHold = null;
    }

    public static void clear() {
        recordUnsuccessfulStandingHandoff(pending != null ? pending.rider()
            : readyToApply != null ? readyToApply.rider()
            : standingRebase != null ? standingRebase.handoff().rider() : null);
        standingCompletion = null;
        standingRebase = null;
        clearMovementHold();
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
        float localYaw,
        RiderIdentity rider
    ) {}

    private record ReadyToApply(
        UUID handoffId,
        ResourceKey<Level> destinationDimension,
        @Nullable DQuaternion rotation,
        boolean teleportChangesGravity,
        TransformationManager.PlayerRotationContext rotationContext,
        float localPitch,
        float localYaw,
        RiderIdentity rider,
        boolean serverInitiated,
        int waitTicks
    ) {
        ReadyToApply withWaitTicks(int ticks) {
            return new ReadyToApply(
                handoffId, destinationDimension, rotation, teleportChangesGravity,
                rotationContext, localPitch, localYaw, rider, serverInitiated, ticks
            );
        }
    }

    private record StandingRebase(LocalPlayer player, ReadyToApply handoff) {}

    private record RiderIdentity(@Nullable UUID vehicleId, ResourceKey<Level> sourceDimension, @Nullable UUID supportId, @Nullable Vec3 supportPosition) {}

    private record PreparedTransform(
        ResourceKey<Level> destinationDimension,
        @Nullable DQuaternion rotation,
        boolean teleportChangesGravity
    ) {}
}
