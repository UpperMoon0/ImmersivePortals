package qouteall.imm_ptl.core.compat.valkyrienskies;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ServerPortalTickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.joml.primitives.AABBic;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.core.apigame.world.ServerShipWorldCore;
import org.valkyrienskies.core.impl.game.ShipTeleportDataImpl;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Optional, source-pinned 1.21.1 native VS ship teleport adapter.
 *
 * <p>The core ship transfer uses VS's own teleportShip API (same ship ID,
 * attachments, native chunk claim), never reassembles blocks. Only the exact
 * 1.21.1 port ABI is supported, and the feature is opt-in until the real
 * dedicated-server/client acceptance suite passes. The code is entirely
 * absent from IP's class loading when VS is not installed.
 *
 * <p>Do not confuse a native body transfer with guaranteed passenger or
 * client synchronisation. Pending transfers remain separately visible and
 * refuse double submission.
 */
public final class VSNativeShipTransport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Long, Pending> PENDING = new HashMap<>();
    private static final Map<Long, Long> COOLDOWN_UNTIL = new HashMap<>();
    private static boolean installed;

    private record Participant(UUID playerId, Vec3 originalPosition, Vec3 destinationPosition,
                               float yaw, float pitch) {}
    private record Pending(long shipId, UUID portalId, String sourceDimension,
                           String destinationDimension, long started,
                           Vector3d sourcePosition, Quaterniond sourceRotation,
                           Vector3d sourceVelocity, Vector3d sourceOmega,
                           List<Participant> players) {}

    private VSNativeShipTransport() {}

    public static void install() {
        if (installed) return;
        installed = true;
        VSRemoteShipyardTracking.install();
        if (!Boolean.getBoolean("ip.vs.enableNativeShipTeleport")) {
            LOGGER.info("VS2 found; native ship teleport is disabled until validated "
                + "with -Dip.vs.enableNativeShipTeleport=true");
            return;
        }
        NeoForge.EVENT_BUS.addListener(ServerPortalTickEvent.class,
            event -> attempt(event.portal));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,
            event -> tickPending(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> {
            PENDING.clear();
            COOLDOWN_UNTIL.clear();
        });
        LOGGER.warn("VS2 native ship teleport is EXPERIMENTAL and requires a pinned "
            + "1.21.1 VS port; Clockwork rider/joint persistence is not yet certified");
    }

    private static ServerShipWorldCore shipWorld(MinecraftServer server) {
        try {
            Object result = server.getClass().getMethod("getShipObjectWorld").invoke(server);
            return result instanceof ServerShipWorldCore world ? world : null;
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static void attempt(Portal portal) {
        // Never mutate an unsupported nonlinear transform, mirror or missing world.
        if (!portal.isPortalValid() || portal instanceof Mirror || portal.hasScaling()
            || portal.level().dimension() == portal.getDestDim()) {
            return;
        }
        MinecraftServer server = portal.getServer();
        if (server == null || !server.isSameThread()) return;
        ServerLevel destination = server.getLevel(portal.getDestDim());
        ServerShipWorldCore ships = shipWorld(server);
        if (destination == null || ships == null) return;
        String sourceId = portal.level().dimension().location().toString();
        String destinationId = portal.getDestDim().location().toString();
        long now = server.overworld().getGameTime();
        for (LoadedServerShip ship : ships.getLoadedShips()) {
            if (!sourceId.equals(ship.getChunkClaimDimension())
                || PENDING.containsKey(ship.getId())
                || COOLDOWN_UNTIL.getOrDefault(ship.getId(), -1L) > now
                || ship.isOnPortalCoolDown()) {
                continue;
            }
            ShipTransform prev = ship.getPrevTickTransform(), current = ship.getTransform();
            AABBic local = ship.getShipAABB();
            AABBdc worldBounds = ship.getWorldAABB();
            if (prev == null || current == null || local == null || worldBounds == null) continue;
            AABB hull = new AABB(local.minX(), local.minY(), local.minZ(),
                local.maxX(), local.maxY(), local.maxZ());
            ShipPortalSweep.Result result = ShipPortalSweep.evaluate(
                prev.getShipToWorld(), current.getShipToWorld(), hull,
                portal.getOriginPos(), portal.getNormal(), portal::isMovedThroughPortal
            );
            if (!result.canBeginTransfer()) continue;
            Vector3dc position = current.getPositionInWorld();
            Vec3 destCenter = portal.transformPoint(
                new Vec3(position.x(), position.y(), position.z()));
            if (!finite(destCenter) ||
                !destination.getWorldBorder().isWithinBounds(net.minecraft.core.BlockPos.containing(destCenter))) {
                continue;
            }

            // A failure here must not leave a partial owner. Snapshot before
            // entering VS native asynchronous physics handoff.
            Vector3d originalPos = new Vector3d(position);
            Quaterniond originalRot = new Quaterniond(current.getShipToWorldRotation());
            Vector3d originalVel = new Vector3d(ship.getVelocity());
            Vector3d originalOmega = new Vector3d(ship.getOmega());
            DQuaternion rotation = portal.getRotationD();
            Quaterniond portalRot = new Quaterniond(
                rotation.x, rotation.y, rotation.z, rotation.w).normalize();
            Quaterniond targetRotation = portalRot.mul(originalRot, new Quaterniond());
            Vec3 velocity = portal.transformLocalVecNonScale(
                new Vec3(originalVel.x(), originalVel.y(), originalVel.z()));
            Vec3 omega = portal.transformLocalVecNonScale(
                new Vec3(originalOmega.x(), originalOmega.y(), originalOmega.z()));
            List<Participant> passengers = collectStandingPlayers(
                (ServerLevel) portal.level(), ship.getId(), worldBounds, portal
            );
            if (!passengers.isEmpty()) {
                // The real rider-dependency/seat network protocol is not
                // implemented yet. Refuse rather than teleport a ship away
                // from its passengers or tear vehicle roots between worlds.
                LOGGER.warn("Refusing native VS transfer for ship {} with {} attached players "
                    + "until correlated passenger handoff is available",
                    ship.getId(), passengers.size());
                continue;
            }

            Pending pending = new Pending(ship.getId(), portal.getUUID(), sourceId,
                destinationId, now, originalPos, originalRot, originalVel, originalOmega, passengers);
            PENDING.put(ship.getId(), pending);
            try {
                ships.teleportShip(ship, new ShipTeleportDataImpl(
                    new Vector3d(destCenter.x, destCenter.y, destCenter.z),
                    targetRotation, new Vector3d(velocity.x, velocity.y, velocity.z),
                    new Vector3d(omega.x, omega.y, omega.z),
                    destinationId, null, null));
                ship.handleInsidePortal();
                COOLDOWN_UNTIL.put(ship.getId(), now + 40);
                LOGGER.info("VS native ship {} teleport submitted: {} -> {} via portal {}",
                    ship.getId(), sourceId, destinationId, pending.portalId());
            } catch (RuntimeException ex) {
                // The native call might already have queued an asynchronous
                // physics mutation. Preserve the pending ledger and report.
                LOGGER.error("VS native teleport failed; ownership is uncertain for ship {}",
                    ship.getId(), ex);
            }
        }
    }

    private static List<Participant> collectStandingPlayers(
        ServerLevel level, long shipId, AABBdc worldAabb, Portal portal) {
        AABB broad = new AABB(worldAabb.minX(), worldAabb.minY(),
            worldAabb.minZ(), worldAabb.maxX(), worldAabb.maxY(), worldAabb.maxZ())
            .inflate(2);
        List<Participant> participants = new ArrayList<>();
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, broad)) {
            // Includes potential standing and mounted occupants. Err on the
            // side of refusing an unsafe transfer rather than omitting them.
            Vec3 destination = portal.transformPoint(player.position());
            participants.add(new Participant(player.getUUID(), player.position(), destination,
                player.getYRot(), player.getXRot()));
        }
        return participants;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y)
            && Double.isFinite(vector.z);
    }

    private static void tickPending(MinecraftServer server) {
        if (!server.isSameThread() || PENDING.isEmpty()) return;
        ServerShipWorldCore world = shipWorld(server);
        if (world == null) return;
        long now = server.overworld().getGameTime();
        for (Pending pending : List.copyOf(PENDING.values())) {
            LoadedServerShip ship = world.getLoadedShips().getById(pending.shipId());
            if (ship == null) {
                // Native body may be temporarily unloaded during cross-world
                // processing; do not create a second ship or auto-delete.
                if (now - pending.started() > 80) {
                    LOGGER.error("VS ship {} disappeared during native handoff; manual "
                        + "recovery required", pending.shipId());
                    PENDING.remove(pending.shipId());
                }
                continue;
            }
            if (pending.destinationDimension().equals(ship.getChunkClaimDimension())) {
                // Native owner transferred. A future production release must
                // require destination client chunk/mesh receipts before Ack.
                LOGGER.info("VS native ship {} changed ownership to {} in {} ticks",
                    pending.shipId(), pending.destinationDimension(), now - pending.started());
                PENDING.remove(pending.shipId());
                continue;
            }
            if (now - pending.started() > 80) {
                // Do not assert rollback: this core's physics move might still
                // be pending. Keep a cooldown and fail visibly.
                LOGGER.error("VS ship {} native transfer to {} not confirmed after 80 ticks. "
                    + "Source ownership {} retained; blocking further attempts",
                    pending.shipId(), pending.destinationDimension(),
                    ship.getChunkClaimDimension());
                PENDING.remove(pending.shipId());
                COOLDOWN_UNTIL.put(pending.shipId(), now + 200);
            }
        }
        COOLDOWN_UNTIL.entrySet().removeIf(e -> e.getValue() <= now);
    }
}
