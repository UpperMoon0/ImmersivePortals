package qouteall.imm_ptl.core.compat.valkyrienskies;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ServerPortalTickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Optional, read-only native VS2 diagnostic bridge.
 *
 * <p>The 1.21.1 community VS port has a different ABI from official VS 2.4.x.
 * This probe uses the public ship-world/ship-transform getters shared by the
 * available 1.21.1 source, without linking their types into a non-VS install.
 * It deliberately does NOT teleport a ship: that would be unsafe until
 * native owner/attachment/rider rollback is proven in a live acceptance run.
 */
public final class ValkyrienSkiesShipProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Object, History> HISTORY = new WeakHashMap<>();
    private static boolean installed;

    private record History(long tick, String dimension, Matrix4d previous, Matrix4d current) {}

    private ValkyrienSkiesShipProbe() {}

    public static void install() {
        if (installed) return;
        installed = true;
        boolean vsLoaded = LoadingModList.get().getModFileById("valkyrienskies") != null;
        if (vsLoaded) {
            try {
                Class.forName(
                    "qouteall.imm_ptl.core.compat.valkyrienskies.VSNativeShipTransport"
                ).getMethod("install").invoke(null);
            } catch (ReflectiveOperationException | LinkageError ex) {
                LOGGER.error("VS2 1.21.1 native API unavailable; integration disabled", ex);
            }
        }
        if (!vsLoaded || !Boolean.getBoolean("ip.vs.shipProbe")) return;
        NeoForge.EVENT_BUS.addListener(ServerPortalTickEvent.class, event ->
            observe(event.portal));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> HISTORY.clear());
        LOGGER.info("Immersive Portals: read-only VS ship portal probe enabled");
    }

    private static void observe(Portal portal) {
        if (!portal.isPortalValid() || portal.hasScaling()) return;
        MinecraftServer server = portal.getServer();
        if (server.getLevel(portal.getDestDim()) == null) return;

        try {
            Object shipWorld = invoke(server, "getShipObjectWorld");
            if (shipWorld == null) return;
            Object loadedShips = invoke(shipWorld, "getLoadedShips");
            if (!(loadedShips instanceof Collection<?> ships)) return;
            long tick = server.overworld().getGameTime();
            String sourceDimension = portal.level().dimension().location().toString();
            for (Object ship : ships) {
                if (ship == null) continue;
                String dimension = String.valueOf(invoke(ship, "getChunkClaimDimension"));
                if (!sourceDimension.equals(dimension)) continue;

                Object transform = invoke(ship, "getTransform");
                Object shipToWorld = invoke(transform, "getShipToWorld");
                if (!(shipToWorld instanceof Matrix4dc matrix) || !matrix.isFinite()) continue;
                Matrix4d pose = new Matrix4d(matrix);
                History history;
                synchronized (HISTORY) {
                    history = HISTORY.get(ship);
                    if (history == null || !dimension.equals(history.dimension())) {
                        HISTORY.put(ship, new History(tick, dimension, null, pose));
                        continue;
                    }
                    if (history.tick() != tick) {
                        history = new History(tick, dimension, history.current(), pose);
                        HISTORY.put(ship, history);
                    }
                }
                if (history.previous() == null) continue;

                Object bounding = invoke(ship, "getShipAABB");
                if (bounding == null) continue;
                AABB localBounds = new AABB(
                    scalar(bounding, "minX"), scalar(bounding, "minY"), scalar(bounding, "minZ"),
                    scalar(bounding, "maxX"), scalar(bounding, "maxY"), scalar(bounding, "maxZ")
                );
                ShipPortalSweep.Result result = ShipPortalSweep.evaluate(
                    history.previous(), history.current(), localBounds,
                    portal.getOriginPos(), portal.getNormal(), portal::isMovedThroughPortal
                );
                if (result.canBeginTransfer()) {
                    LOGGER.info("VS ship {} swept through portal {} from {} to {} ({} boundary samples); "
                            + "read-only probe, no physics mutation",
                        invoke(ship, "getId"), portal.getUUID(),
                        sourceDimension, portal.getDestDim().location(), result.crossedSamples());
                } else if (result.status() == ShipPortalSweep.Status.APERTURE_MISS) {
                    LOGGER.debug("VS ship {} misses portal aperture: {} out of {} samples",
                        invoke(ship, "getId"), result.rejectedSamples(), result.crossedSamples());
                }
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            LOGGER.error("VS ship probe cannot inspect the installed native API; "
                + "do not enable physics transfer against an unverified fork", ex);
            // Fail closed: a changed native ABI must not spam or attempt transfer.
            HISTORY.clear();
        }
    }

    private static double scalar(Object object, String method) throws ReflectiveOperationException {
        return ((Number) invoke(object, method)).doubleValue();
    }

    private static Object invoke(Object object, String name) throws ReflectiveOperationException {
        try {
            Method method = object.getClass().getMethod(name);
            return method.invoke(object);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw ex;
        }
    }
}
