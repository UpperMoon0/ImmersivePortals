package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real assembly and native-physics flight. Never teleports a ship or invokes the compatibility transfer API. */
public final class SableShipFlightTest {
    private enum Phase { LOGIN, ASSEMBLY, SETTLE, OBSERVE, FLY, CLEAR_SEAM, DONE }
    private static final Gson JSON = new Gson();
    private static final double SPEED = 48;
    private static ServerPlayer player;
    private static ServerLevel source, destination, current;
    private static SableFlightShipFixture fixture;
    private static Portal forward, reverse;
    private static UUID seatId;
    private static Phase phase = Phase.LOGIN;
    private static int ticks, phaseTicks, caseIndex, leg;
    private static int flightTicks;
    private static double observedFlightDistance;
    private static Vector3d hover, launch, previousVelocity;
    private static Quaterniond previousOrientation;
    private static Map<String, Object> caseReport, crossing;
    private static final List<Map<String, Object>> cases = new ArrayList<>();
    private static final List<Map<String, Object>> crossings = new ArrayList<>();
    private static String token;

    static boolean enabled() { return "true".equalsIgnoreCase(System.getenv("IP_SABLE_SHIPS")); }
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer joined)) return;
        player = joined;
        source = joined.server.getLevel(Level.OVERWORLD);
        destination = joined.server.getLevel(Level.NETHER);
        require(source != null && destination != null, "missing test dimensions");
    }
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled() || player == null || phase == Phase.DONE) return;
        try {
            ticks++; phaseTicks++;
            require(ticks < 18000, "overall timeout: " + phase + " case=" + caseIndex + " leg=" + leg);
            require(phaseTicks < 2400, "phase timeout: " + phase + " case=" + caseIndex + " leg=" + leg);
            switch (phase) {
                case LOGIN -> { if (phaseTicks >= 40) setup(); }
                case ASSEMBLY -> mountWhenReady();
                case SETTLE -> settle();
                case OBSERVE -> observe();
                case FLY -> fly();
                case CLEAR_SEAM -> clearSeam();
                case DONE -> { }
            }
        } catch (Throwable error) {
            phase = Phase.DONE;
            error.printStackTrace();
            PortalSmokeSupport.write("server-fail.txt", "ships case=" + caseIndex + " leg=" + leg + ": " + error);
        }
    }
    private static void phase(Phase next) { phase = next; phaseTicks = 0; }
    private static boolean stack() { return caseIndex % 2 == 1; }
    private static void setup() {
        crossings.clear(); leg = -1;
        current = source;
        // Only the disposable flat world's bedrock/soil below this fixture is removed. No personal world is used.
        if (stack()) for (int x = -80; x <= 80; x++) for (int z = -80; z <= 80; z++)
            for (int y = source.getMinBuildHeight(); y < source.getMinBuildHeight() + 5; y++)
                source.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        if (stack()) {
            // The Nether connector is at logical ceiling 128, not max build height 256.
            // Clear the entire rotated ship corridor, including the roof and the braking zone.
            int ceiling = qouteall.imm_ptl.core.McHelper.getMaxContentYExclusive(destination);
            for (int x = -48; x <= 48; x++) for (int z = -48; z <= 48; z++)
                for (int clearY = ceiling - 48; clearY <= ceiling + 20; clearY++) {
                    BlockPos position = new BlockPos(x, clearY, z);
                    if (!destination.getBlockState(position).isAir())
                        destination.setBlock(position, Blocks.AIR.defaultBlockState(), 2);
                }
        }
        int y = stack() ? source.getMinBuildHeight() + 40 : 160;
        if (stack()) {
            VerticalConnectingPortal.connectMutually(Level.OVERWORLD, Level.NETHER, false);
            forward = VerticalConnectingPortal.getConnectingPortal(source, VerticalConnectingPortal.ConnectorType.floor);
            reverse = VerticalConnectingPortal.getConnectingPortal(destination, VerticalConnectingPortal.ConnectorType.ceil);
            require(forward != null && reverse != null, "dimension-stack connectors absent");
            var rotation = DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), 90);
            forward.setRotation(rotation);
            PortalManipulation.rotatePortalBody(reverse, rotation);
            reverse.setRotation(rotation.getConjugated());
            GlobalPortalStorage.get(source).onDataChanged();
            GlobalPortalStorage.get(destination).onDataChanged();
        } else {
            forward = Portal.ENTITY_TYPE.create(source);
            require(forward != null, "cannot create ordinary portal");
            forward.setOriginPos(new Vec3(0, 120, 0));
            // X cross -Z gives +Y, so the vessel approaches the front while descending.
            forward.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 0, -1), 160, 160);
            forward.setDestinationDimension(Level.NETHER);
            forward.setDestination(new Vec3(0, 224, 0));
            forward.setRotation(DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), 90));
            require(source.addFreshEntity(forward), "ordinary portal spawn failed");
            reverse = PortalManipulation.createReversePortal(forward, Portal.ENTITY_TYPE);
            require(destination.addFreshEntity(reverse), "reverse portal spawn failed");
        }
        var size = caseIndex < 2 ? SableFlightShipFixture.Size.CONTROL : SableFlightShipFixture.Size.LARGE;
        fixture = SableFlightShipFixture.assemble(source, new BlockPos(0, y, 0), size);
        ServerSubLevel body = fixture.requireBody(source);
        SubLevelContainer.getContainer(source).addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        hover = new Vector3d(body.logicalPose().position());
        caseReport = new LinkedHashMap<>();
        caseReport.put("size", size.name()); caseReport.put("route", stack() ? "dimension-stack" : "ordinary");
        caseReport.put("ship_id", fixture.id().toString()); caseReport.put("assembled", true);
        caseReport.put("dimensions", caseIndex < 2 ? List.of(7,5,11) : List.of(49,17,65));
        caseReport.put("blocks", fixture.assembledBlockCount());
        caseReport.put("chunks", fixture.plotChunks());
        caseReport.put("propulsion", "native rigid-body velocity launch; not an Aeronautics engine acceptance test");
        phase(Phase.ASSEMBLY);
    }
    private static void mountWhenReady() {
        var body = fixture.requireBody(current);
        if (RigidBodyHandle.of(body) == null || !RigidBodyHandle.of(body).isValid()) return;
        hold(body);
        if (phaseTicks < 20) return;
        var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("create:seat"));
        Entity seat = type.create(current);
        require(seat != null, "Create seat type absent");
        var position = fixture.seatPosition(body);
        seat.setPos(position.getX()+.5, position.getY(), position.getZ()+.5);
        require(current.addFreshEntity(seat), "cannot spawn retained seat");
        require(Sable.HELPER.getContaining(seat) == body, "seat outside assembled body");
        seatId = seat.getUUID();
        require(player.startRiding(seat, true), "rider could not mount assembled ship");
        fixture.setMotorSpeed(current, body, rpm());
        fixture.mutateProbe(current, body, mutated());
        phase(Phase.SETTLE);
    }
    private static int rpm() { return leg % 2 == 0 ? -32 : 16; }
    private static boolean mutated() { return leg % 2 == 0; }
    private static void settle() {
        var body = fixture.requireBody(current); hold(body);
        if (phaseTicks < 30) return;
        fixture.verifyIntegrity(current, body);
        fixture.verifyMachinery(current, body, rpm());
        verifyRider(body);
        token = caseIndex + ":" + leg;
        var request = new LinkedHashMap<String,Object>(caseReport);
        request.put("token", token); request.put("leg", leg); request.put("dimension", current.dimension().location().toString());
        request.put("seat_id", seatId.toString()); request.put("rpm", rpm()); request.put("mutated", mutated());
        request.put("blocks_manifest", fixture.expectedBlockStates().entrySet().stream().map(entry -> {
            BlockPos p = entry.getKey();
            return List.of(p.getX(), p.getY(), p.getZ(), Block.getId(entry.getValue()));
        }).toList());
        var kinetics = new ArrayList<List<Number>>();
        for (BlockPos p : fixture.expectedBlockStates().keySet()) {
            var be = current.getBlockEntity(body.getPlot().getCenterBlock().offset(p));
            if (be instanceof KineticBlockEntity kinetic) kinetics.add(List.of(p.getX(), p.getY(), p.getZ(), kinetic.getSpeed()));
        }
        require(!kinetics.isEmpty(), "no functional machinery snapshots");
        request.put("kinetics", kinetics);
        request.put("from", leg % 2 == 0 ? "minecraft:overworld" : "minecraft:the_nether");
        request.put("to", current.dimension().location().toString());
        PortalSmokeSupport.write("ship-observe.json", JSON.toJson(request));
        phase(Phase.OBSERVE);
    }
    private static void observe() {
        var body = fixture.requireBody(current); hold(body);
        if (!PortalSmokeSupport.read("ship-client-ack.txt").equals(token)) return;
        if (leg >= 0) { crossing.put("client_ack", true); crossings.add(crossing); }
        if (leg == 3) {
            caseReport.put("crossings", List.copyOf(crossings)); cases.add(caseReport);
            cleanup(); caseIndex++;
            if (caseIndex == 4) {
                PortalSmokeSupport.write("server-ships.json", JSON.toJson(Map.of("schema_version",1,"cases",cases)));
                PortalSmokeSupport.write("server-pass.txt", "Four genuinely assembled ship cases completed sixteen native crossings with rider/cargo/machinery continuity");
                phase(Phase.DONE);
            } else setup();
            return;
        }
        leg++;
        launch = new Vector3d(body.logicalPose().position()); flightTicks = 0; observedFlightDistance = 0;
        // Nonzero lateral components make the yaw-rotated velocity assertion nonvacuous.
        // The reverse launch points back along the transformed outward trajectory.
        setVelocity(body, leg % 2 == 0 ? new Vector3d(8, -SPEED, 4) : new Vector3d(-4, SPEED, 8));
        previousVelocity = handle(body).getLinearVelocity(new Vector3d());
        previousOrientation = new Quaterniond(body.logicalPose().orientation());
        phase(Phase.FLY);
    }
    private static void fly() {
        flightTicks++;
        ServerLevel target = leg % 2 == 0 ? destination : source;
        var arriving = SubLevelContainer.getContainer(target).getSubLevel(fixture.id());
        if (!(arriving instanceof ServerSubLevel body)) {
            var active = fixture.requireBody(current);
            observedFlightDistance = Math.max(observedFlightDistance, launch.distance(active.logicalPose().position()));
            previousVelocity = handle(active).getLinearVelocity(new Vector3d());
            previousOrientation = new Quaterniond(active.logicalPose().orientation());
            require(phaseTicks < 400, "native ship never crossed portal");
            return;
        }
        require(SubLevelContainer.getContainer(current).getSubLevel(fixture.id()) == null, "duplicate source body after native crossing");
        Portal portal = leg % 2 == 0 ? forward : reverse;
        Vec3 expected = portal.transformLocalVec(new Vec3(previousVelocity.x,previousVelocity.y,previousVelocity.z));
        Vector3d actual = handle(body).getLinearVelocity(new Vector3d());
        require(actual.isFinite() && actual.distance(new Vector3d(expected.x,expected.y,expected.z)) < 5,
            "velocity discontinuity at seam: expected=" + expected + " actual=" + actual);
        var rotation = portal.getRotation();
        var expectedOrientation = new Quaterniond(previousOrientation);
        if (rotation != null) expectedOrientation.premul(new Quaterniond(rotation.x,rotation.y,rotation.z,rotation.w));
        require(Math.abs(expectedOrientation.dot(body.logicalPose().orientation())) > .999,
            "orientation discontinuity at rotated seam");
        double distance = observedFlightDistance;
        require(flightTicks >= 5 && distance >= 8, "insufficient genuine pre-seam flight");
        current = target;
        fixture.verifyIntegrity(current, body); verifyRider(body);
        crossing = new LinkedHashMap<>();
        crossing.put("leg",leg); crossing.put("from",portal.level().dimension().location().toString());
        crossing.put("to",current.dimension().location().toString());
        crossing.put("native_motion_ticks",flightTicks); crossing.put("native_distance",distance);
        crossing.put("unique_body",true); crossing.put("rider_graph",true); crossing.put("integrity",true);
        crossing.put("velocity",true); crossing.put("orientation",true);
        crossing.put("arrival_speed",actual.length());
        phase(Phase.CLEAR_SEAM);
    }
    private static void clearSeam() {
        var body = fixture.requireBody(current);
        Portal portal = leg % 2 == 0 ? reverse : forward;
        double clearance = Math.abs(body.logicalPose().position().y() - portal.getOriginPos().y);
        require(phaseTicks < 300,"ship failed to clear seam");
        if (clearance < 28) return;
        hover = new Vector3d(body.logicalPose().position()); hold(body);
        fixture.setMotorSpeed(current,body,rpm()); fixture.mutateProbe(current,body,mutated());
        phase(Phase.SETTLE);
    }
    private static void verifyRider(ServerSubLevel body) {
        Entity seat = current.getEntity(seatId);
        require(seat != null && Sable.HELPER.getContaining(seat) == body,"retained seat missing from ship");
        require(player.serverLevel() == current && player.getVehicle() == seat && seat.getPassengers().contains(player),"rider separated from vessel");
        ServerLevel other = current == source ? destination : source;
        require(other.getEntity(seatId) == null,"duplicated retained seat");
    }
    private static void hold(ServerSubLevel body) {
        // Stationkeeping is native velocity feedback only. There is no ship teleport, even at observation barriers.
        Vector3d correction = new Vector3d(hover).sub(body.logicalPose().position()).mul(5);
        if (correction.length() > 8) correction.normalize(8);
        setVelocity(body,correction);
    }
    private static RigidBodyHandle handle(ServerSubLevel body) {
        var handle = RigidBodyHandle.of(body); require(handle != null && handle.isValid(),"native physics handle missing"); return handle;
    }
    private static void setVelocity(ServerSubLevel body, Vector3d velocity) {
        var handle = handle(body);
        handle.addLinearAndAngularVelocity(velocity.sub(handle.getLinearVelocity(new Vector3d())),
            handle.getAngularVelocity(new Vector3d()).negate());
    }
    private static void cleanup() {
        player.stopRiding();
        Entity seat = current.getEntity(seatId); if (seat != null) seat.discard();
        var container = SubLevelContainer.getContainer(current);
        container.removeSubLevel(fixture.requireBody(current),SubLevelRemovalReason.REMOVED);
        if (stack()) {
            GlobalPortalStorage.get(source).removePortal(forward);
            GlobalPortalStorage.get(destination).removePortal(reverse);
        } else { forward.discard(); reverse.discard(); }
        player.teleportTo(source,0,200,0,0,0);
    }
    private static void require(boolean value,String message) { if (!value) throw new IllegalStateException(message); }
}
