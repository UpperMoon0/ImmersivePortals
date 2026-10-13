package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
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

/** Real assembly and native-physics flight. No direct ship transfer; a separate stale-request negative regression must not migrate anything. */
public final class SableShipFlightTest {
    private enum Phase { LOGIN, PREPARE_CORRIDOR, ASSEMBLY, SETTLE, OBSERVE, FLY, CLEAR_SEAM, STOPPED_ANIMATION, DONE }
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
    private static int forceSamples;
    private static double peakSignedAcceleration;
    private static double flightGravityY;
    private static SableAeronauticsShipDrive.MotionSample poweredStart;
    private static Map<String,Object> poweredMotion;
    private static Vector3d hover, launch, previousVelocity;
    private static Quaterniond previousOrientation;
    private static Pose3d previousPose;
    private static int corridorVisited, corridorChanged, corridorTicks;
    private static long corridorStartedNanos, corridorMaxTickNanos;
    private static Map<String,Object> corridorReceipt;
    private static Map<String,Object> sourceCorridorReceipt;
    private static boolean preparingSourceCorridor;
    private static Map<String, Object> caseReport, crossing;
    private static final List<Map<String, Object>> cases = new ArrayList<>();
    private static final List<Map<String, Object>> crossings = new ArrayList<>();
    private static final List<Map<String, Object>> stoppedAnimationTrace = new ArrayList<>();
    private static final List<Map<String,Object>> broadsideSamples = new ArrayList<>();
    private static final Map<String,Map<String,Object>> broadsideStages = new LinkedHashMap<>();
    private static Map<String,Object> broadsideLaunch;
    private static int broadsideTicks;
    private static boolean broadsideInFlight;
    private static String token;
    private static Portal stoppedAnimationPortal;
    private static String stoppedAnimationStage;
    private static Vec3 stoppedAnimationEye;
    private static Vector3d stoppedAnimationStart;
    private static int stoppedAnimationDrainedTicks;
    private static long stoppedAnimationStartTick;
    private static long stoppedAnimationCrossedTick;
    private static Vec3 stoppedAnimationPreviousOrigin;
    private static Map<String,Object> stoppedAnimationReceipt;

    static boolean aeronautics() { return "true".equalsIgnoreCase(System.getenv("IP_SABLE_SHIPS_AERONAUTICS")); }
    static Map<String,String> addonVersions() { return Map.of("aeronautics","1.3.1","simulated","1.3.1","offroad","1.3.1"); }
    static String selectedSize() { return System.getenv().getOrDefault("IP_SABLE_SHIPS_SIZE", "all"); }
    static List<Integer> selectedCaseIndices() {
        var result = new ArrayList<Integer>();
        if (!selectedSize().equals("large")) { result.add(0); if (!aeronautics()) result.add(1); }
        if (!selectedSize().equals("control")) { result.add(2); if (!aeronautics()) result.add(3); }
        if (!aeronautics()) {
            if (!selectedSize().equals("large")) result.add(4);
            if (!selectedSize().equals("control")) result.add(5);
        }
        return List.copyOf(result);
    }
    static int expectedCases() { return selectedCaseIndices().size(); }
    static int expectedCrossingsForRoute(String route) { return route.equals("vertical-broadside") ? 2 : 4; }
    static int expectedCrossings() { return (selectedSize().equals("all") ? 2 : 1) * (aeronautics() ? 4 : 10); }
    private static boolean broadside() { return caseIndex >= 4; }
    private static boolean large() { return caseIndex == 2 || caseIndex == 3 || caseIndex == 5; }
    private static String route() { return broadside() ? "vertical-broadside" : stack() ? "dimension-stack" : "ordinary"; }
    static boolean enabled() { return "true".equalsIgnoreCase(System.getenv("IP_SABLE_SHIPS")); }
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer joined)) return;
        require(List.of("all", "control", "large").contains(selectedSize()), "invalid ship size filter");
        caseIndex = selectedCaseIndices().getFirst();
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
                case PREPARE_CORRIDOR -> prepareCorridor();
                case ASSEMBLY -> mountWhenReady();
                case SETTLE -> settle();
                case OBSERVE -> observe();
                case FLY -> fly();
                case CLEAR_SEAM -> clearSeam();
                case STOPPED_ANIMATION -> stoppedAnimationProbe();
                case DONE -> { }
            }
        } catch (Throwable error) {
            if (stoppedAnimationPortal!=null) { stoppedAnimationPortal.discard(); stoppedAnimationPortal=null; }
            if (stoppedAnimationStage!=null) writeStoppedAnimationControl("resume");
            checkpoint("failed", error.toString());
            phase = Phase.DONE;
            error.printStackTrace();
            PortalSmokeSupport.write("server-fail.txt", "ships case=" + caseIndex + " leg=" + leg + ": " + error);
        }
    }
    private static void phase(Phase next) { phase = next; phaseTicks = 0; }
    private static boolean stack() { return !broadside() && caseIndex % 2 == 1; }
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
        if (aeronautics() || broadside()) {
            // Low-altitude routes need a prepared swept-hull envelope before any portal
            // or ship exists. The horizontal powered aperture fits wholly inside it;
            // the vertical portal extends above it, but the entire broadside arc must not.
            preparingSourceCorridor=broadside(); sourceCorridorReceipt=null;
            resetCorridorProgress();
            phase(Phase.PREPARE_CORRIDOR);
            return;
        }
        finishSetup();
    }
    private static void resetCorridorProgress() {
        corridorVisited=0; corridorChanged=0; corridorTicks=0; corridorMaxTickNanos=0;
        corridorStartedNanos=System.nanoTime(); corridorReceipt=null;
    }
    private static void prepareCorridor() {
        ServerLevel corridorLevel=preparingSourceCorridor ? source : destination;
        long started=System.nanoTime(); int batch=0;
        var position=new BlockPos.MutableBlockPos();
        while (corridorVisited<SableShipFlightCorridor.VOLUME && batch<SableShipFlightCorridor.POSITIONS_PER_TICK) {
            int index=corridorVisited;
            position.set(SableShipFlightCorridor.x(index),SableShipFlightCorridor.y(index),SableShipFlightCorridor.z(index));
            if (!corridorLevel.getBlockState(position).isAir()) {
                require(corridorLevel.setBlock(position,Blocks.AIR.defaultBlockState(),2),"could not clear flight corridor at "+position);
                corridorChanged++;
            }
            corridorVisited++; batch++;
            // Cooperative budget: a single chunk generation may itself take longer.
            if (System.nanoTime()-started>=SableShipFlightCorridor.SOFT_NANOS_PER_TICK) break;
        }
        corridorTicks++; corridorMaxTickNanos=Math.max(corridorMaxTickNanos,System.nanoTime()-started);
        boolean complete=corridorVisited==SableShipFlightCorridor.VOLUME;
        corridorReceipt=new LinkedHashMap<>();
        corridorReceipt.put("dimension",corridorLevel.dimension().location().toString());
        corridorReceipt.put("min",List.of(SableShipFlightCorridor.MIN_XZ,SableShipFlightCorridor.MIN_Y,SableShipFlightCorridor.MIN_XZ));
        corridorReceipt.put("max_inclusive",List.of(SableShipFlightCorridor.MAX_XZ,SableShipFlightCorridor.MAX_Y,SableShipFlightCorridor.MAX_XZ));
        corridorReceipt.put("total_positions",SableShipFlightCorridor.VOLUME);
        corridorReceipt.put("visited_positions",corridorVisited); corridorReceipt.put("changed_blocks",corridorChanged);
        corridorReceipt.put("setup_ticks",corridorTicks); corridorReceipt.put("elapsed_ms",(System.nanoTime()-corridorStartedNanos)/1_000_000.0);
        corridorReceipt.put("max_batch_ms",corridorMaxTickNanos/1_000_000.0);
        corridorReceipt.put("positions_per_tick_limit",SableShipFlightCorridor.POSITIONS_PER_TICK);
        corridorReceipt.put("soft_tick_budget_ms",SableShipFlightCorridor.SOFT_NANOS_PER_TICK/1_000_000.0);
        corridorReceipt.put("complete",complete); corridorReceipt.put("setup_only",true);
        if (complete || corridorTicks%20==0) PortalSmokeSupport.write(
            broadside() ? "broadside-corridor-"+(preparingSourceCorridor ? "source" : "destination")+".json" : "powered-corridor.json",
            JSON.toJson(corridorReceipt));
        if (complete) {
            if (preparingSourceCorridor) {
                sourceCorridorReceipt=new LinkedHashMap<>(corridorReceipt);
                preparingSourceCorridor=false; resetCorridorProgress();
            } else finishSetup();
        }
    }
    private static void finishSetup() {
        int y = broadside() ? SableShipBroadsideGeometry.ASSEMBLY_Y : stack() ? source.getMinBuildHeight() + 40 : aeronautics() ? 32 : 160;
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
            if (broadside()) {
                forward.setOriginPos(new Vec3(0, SableShipBroadsideGeometry.PORTAL_Y, 0));
                // -Z cross +Y gives +X. The unrotated ship's long/nose axis remains Z.
                forward.setOrientationAndSize(new Vec3(0, 0, -1), new Vec3(0, 1, 0),
                    SableShipBroadsideGeometry.PORTAL_WIDTH, SableShipBroadsideGeometry.PORTAL_HEIGHT);
            } else {
                forward.setOriginPos(new Vec3(0, aeronautics() ? -8 : 120, 0));
                // X cross -Z gives +Y, so the vessel approaches the front while descending.
                forward.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 0, -1), 160, 160);
            }
            forward.setDestinationDimension(Level.NETHER);
            forward.setDestination(new Vec3(0, broadside() ? SableShipBroadsideGeometry.PORTAL_Y : aeronautics() ? 48 : 224, 0));
            forward.setRotation(DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), 90));
            require(source.addFreshEntity(forward), "ordinary portal spawn failed");
            reverse = PortalManipulation.createReversePortal(forward, Portal.ENTITY_TYPE);
            require(destination.addFreshEntity(reverse), "reverse portal spawn failed");
        }
        var size = large() ? SableFlightShipFixture.Size.LARGE : SableFlightShipFixture.Size.CONTROL;
        int x = broadside() ? SableFlightShipLayout.create(large()).halfWidth() + (int) SableShipBroadsideGeometry.START_GAP : 0;
        fixture = SableFlightShipFixture.assemble(source, new BlockPos(x, y, 0), size);
        ServerSubLevel body = fixture.requireBody(source);
        SubLevelContainer.getContainer(source).addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        hover = new Vector3d(body.logicalPose().position());
        caseReport = new LinkedHashMap<>();
        caseReport.put("size", size.name()); caseReport.put("route", route());
        caseReport.put("ship_id", fixture.id().toString()); caseReport.put("assembled", true);
        caseReport.put("dimensions", large() ? List.of(49,17,65) : List.of(7,5,11));
        caseReport.put("blocks", fixture.assembledBlockCount());
        caseReport.put("chunks", fixture.plotChunks());
        caseReport.put("propulsion", aeronautics() ? "real Aeronautics propeller actors; no native velocity writes during powered intervals" : "native rigid-body velocity launch; not an Aeronautics engine acceptance test");
        if (aeronautics() || broadside()) caseReport.put("prepared_corridor",corridorReceipt);
        if (broadside()) caseReport.put("prepared_source_corridor",sourceCorridorReceipt);
        if (broadside()) caseReport.put("broadside_geometry",Map.of(
            "portal_normal",List.of(1,0,0),"portal_width",SableShipBroadsideGeometry.PORTAL_WIDTH,
            "portal_height",SableShipBroadsideGeometry.PORTAL_HEIGHT,"portal_center_y",SableShipBroadsideGeometry.PORTAL_Y,
            "ship_nose_local",List.of(0,0,1),"travel_axis_local",List.of(1,0,0),
            "yaw_degrees",90,"launch_kind","native-ballistic","flight_pose_writes",0));
        if (broadside()) caseReport.put("visual_sampling",Map.of(
            "states",List.of("before-launch","after-outbound","after-return"),
            "in_flight_stage_screenshots",false,
            "reason","Native flight is never paused for asynchronous client rendering; stage poses and hull passage are recorded server-side, while real graphical checks run before launch and after full clearance"));
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
        prepareDrive(body);
        phase(Phase.SETTLE);
    }
    private static int nextDriveCommand() { return (leg + 1) % 2 == 0 ? -256 : 256; }
    private static void prepareDrive(ServerSubLevel body) {
        if (aeronautics()) fixture.drive().setVerticalCommand(current,body,nextDriveCommand());
    }
    private static int rpm() { return leg % 2 == 0 ? -32 : 16; }
    private static boolean mutated() { return leg % 2 == 0; }
    private static void settle() {
        var body = fixture.requireBody(current); hold(body);
        if (phaseTicks < (aeronautics() ? 60 : 30)) return;
        caseReport.put("integrity_snapshot",fixture.verifyIntegrity(current, body));
        fixture.verifyMachinery(current, body, rpm());
        verifyRider(body);
        if (aeronautics()) {
            var budget=fixture.drive().liftBudget(current,body);
            require(((Number)budget.get("staticLiftWeightRatio")).doubleValue()>1.1,"powered fixture has insufficient natural-atmosphere lift reserve: "+budget);
            caseReport.put("lift_budget",budget);
            fixture.drive().verifyThrust(current,body,-nextDriveCommand());
        }
        token = caseIndex + ":" + leg;
        var request = new LinkedHashMap<String,Object>(caseReport);
        request.put("token", token); request.put("leg", leg); request.put("dimension", current.dimension().location().toString());
        if (aeronautics()) request.put("propeller_rpm",-nextDriveCommand());
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
        // After cleanup has expired, exercise the real server request entry point with
        // an intentionally stale opposite prediction. This is a negative regression, not flight input.
        if (leg==3) {
            if (phaseTicks<120) return;
            require(handle(body).getLinearVelocity(new Vector3d()).y>=0, "negative regression requires outward/stationary body motion");
            var manager=qouteall.imm_ptl.core.teleportation.ServerTeleportationManager.of(player.server);
            require(!manager.isJustTeleported(player,1),"negative regression needs a quiet request interval");
            manager.onPlayerTeleportedInClient(
                player,current.dimension(),forward.getOriginPos().subtract(forward.getNormal().scale(2)),forward.getUUID());
            require(manager.isJustTeleported(player,1),"stale request did not resolve the real portal entry point");
            require(fixture.requireBody(current)==body,"stale opposite request moved native ship");
            verifyRider(body);
            require(SubLevelContainer.getContainer(destination).getSubLevel(fixture.id())==null,"stale request duplicated native ship");
            caseReport.put("stale_request_rejected",true);
        }
        if (leg >= 0) { crossing.put("client_ack", true); crossings.add(crossing); }
        checkpoint("running", "");
        if (leg == expectedCrossingsForRoute(route()) - 1) {
            caseReport.put("crossings", List.copyOf(crossings));
            if (!stack() && !broadside()) {
                stoppedAnimationStage="pause";
                writeStoppedAnimationControl("pause");
                phase(Phase.STOPPED_ANIMATION);
            } else finishCase();
            return;
        }
        leg++;
        launch = new Vector3d(body.logicalPose().position()); flightTicks = 0; observedFlightDistance = 0;
        // Nonzero lateral components make the yaw-rotated velocity assertion nonvacuous.
        // The reverse launch points back along the transformed outward trajectory.
        if (aeronautics()) {
            poweredStart = fixture.drive().sample(body); forceSamples=0; peakSignedAcceleration=0; poweredMotion=null;
            flightGravityY=((Number)fixture.drive().liftBudget(current,body).get("gravityY")).doubleValue();
            // Motors have spooled under the explicitly separate observation hold. Release feedback now.
            // No direct velocity, force, pose or gravity writes occur through FLY or the seam-clearance interval.
        } else if (broadside()) launchBroadside(body);
        else setVelocity(body, leg % 2 == 0 ? new Vector3d(8, -SPEED, 4) : new Vector3d(-4, SPEED, 8));
        previousVelocity = handle(body).getLinearVelocity(new Vector3d());
        previousOrientation = new Quaterniond(body.logicalPose().orientation());
        previousPose = new Pose3d(body.logicalPose());
        phase(Phase.FLY);
    }
    private static void finishCase() {
        cases.add(caseReport);
        cleanup();
        if (cases.size() == expectedCases()) {
            var report = new LinkedHashMap<String,Object>(); report.put("schema_version",1); report.put("cases",cases);
            if (aeronautics()) report.put("addons",addonVersions());
            PortalSmokeSupport.write("server-ships.json", JSON.toJson(report));
            PortalSmokeSupport.write("server-pass.txt", expectedCases() + " assembled ship cases completed " + expectedCrossings() + " native crossings with rider/cargo/machinery continuity; aeronautics=" + aeronautics());
            phase(Phase.DONE);
        } else { caseIndex = selectedCaseIndices().get(cases.size()); setup(); }
    }
    private static String stoppedAnimationToken() { return caseIndex+":stopped-animation"; }
    private static void writeStoppedAnimationControl(String mode) {
        PortalSmokeSupport.write("stopped-animation-control.json",JSON.toJson(Map.of(
            "token",stoppedAnimationToken(),"mode",mode,"ship_id",fixture.id().toString(),
            "seat_id",seatId.toString(),"source",source.dimension().location().toString(),
            "destination",destination.dimension().location().toString(),"size",fixture.size().name(),
            "route","ordinary")));
    }
    /** Held-body compatibility probe, deliberately outside and excluded from all four flight intervals. */
    private static void stoppedAnimationProbe() {
        var body=fixture.requireBody(current);
        hold(body);
        String ack=PortalSmokeSupport.read("stopped-animation-client-ack.txt");
        if (stoppedAnimationStage.equals("pause")) {
            if (!ack.equals(stoppedAnimationToken()+":paused")) return;
            verifyRider(body);
            // Finished route portals cannot interfere with this separately labelled regression.
            forward.discard(); reverse.discard();
            Entity seat=current.getEntity(seatId);
            Vec3 localEye=seat.getPassengerRidingPosition(player).add(qouteall.imm_ptl.core.McHelper.getEyeOffset(player));
            stoppedAnimationEye=body.logicalPose().transformPosition(localEye);
            Vector3d center=body.logicalPose().transformPosition(new Vector3d(body.getSelfMassTracker().getCenterOfMass()));
            Vector3d normal=new Vector3d(stoppedAnimationEye.x-center.x,stoppedAnimationEye.y-center.y,stoppedAnimationEye.z-center.z);
            double eyeOffset=normal.length();
            require(eyeOffset>.25,"stopped-animation fixture eye is indistinguishable from COM");
            normal.normalize();
            double sweepHalf=Math.min(.75,eyeOffset*.25);
            Vector3d axisW=new Vector3d(Math.abs(normal.y)<.9?0:1,Math.abs(normal.y)<.9?1:0,0).cross(normal).normalize();
            Vector3d axisH=new Vector3d(normal).cross(axisW).normalize();
            Vec3 direction=new Vec3(normal.x,normal.y,normal.z);
            stoppedAnimationPortal=Portal.ENTITY_TYPE.create(current);
            require(stoppedAnimationPortal!=null,"cannot create animated regression portal");
            stoppedAnimationPortal.setOriginPos(stoppedAnimationEye.subtract(direction.scale(sweepHalf)));
            stoppedAnimationPortal.setOrientationAndSize(new Vec3(axisW.x,axisW.y,axisW.z),new Vec3(axisH.x,axisH.y,axisH.z),160,160);
            stoppedAnimationPortal.setDestinationDimension(destination.dimension());
            stoppedAnimationPortal.setDestination(new Vec3(0,aeronautics()?48:224,0));
            require(current.addFreshEntity(stoppedAnimationPortal),"animated regression portal spawn failed");
            stoppedAnimationPortal.addThisSideAnimationDriver(new qouteall.imm_ptl.core.portal.animation.NormalAnimation(
                List.of(new qouteall.imm_ptl.core.portal.animation.NormalAnimation.Phase(20,
                    new qouteall.imm_ptl.core.portal.animation.DeltaUnilateralPortalState(direction.scale(sweepHalf*2),null,null),
                    qouteall.imm_ptl.core.portal.animation.TimingFunction.linear)),current.getGameTime(),1,false));
            stoppedAnimationTrace.clear();
            recordStoppedAnimationWitness("created");
            stoppedAnimationStart=new Vector3d(body.logicalPose().position());
            stoppedAnimationStartTick=current.getGameTime(); stoppedAnimationDrainedTicks=0;
            stoppedAnimationCrossedTick=-1;
            stoppedAnimationPreviousOrigin=stoppedAnimationPortal.getOriginPos();
            stoppedAnimationStage="animate";
            return;
        }
        if (stoppedAnimationStage.equals("animate")) {
            recordStoppedAnimationWitness("animate");
            require(SubLevelContainer.getContainer(destination).getSubLevel(fixture.id())==null,
                "held animated probe migrated before its delayed request");
            Vector3d center=body.logicalPose().transformPosition(new Vector3d(body.getSelfMassTracker().getCenterOfMass()));
            require(stoppedAnimationPortal.getDistanceToPlane(new Vec3(center.x,center.y,center.z))<-.05,
                "animated probe COM must remain behind the source plane");
            require(stoppedAnimationStart.distance(body.logicalPose().position())<1,
                "held animated probe drifted instead of using source-plane motion");
            if (stoppedAnimationCrossedTick<0 && stoppedAnimationPortal.getDistanceToPlane(stoppedAnimationEye)<0)
                stoppedAnimationCrossedTick=current.getGameTime();
            double sourcePlaneSpeed=stoppedAnimationPortal.getOriginPos().distanceTo(stoppedAnimationPreviousOrigin)*20;
            stoppedAnimationPreviousOrigin=stoppedAnimationPortal.getOriginPos();
            boolean drained=!stoppedAnimationPortal.animation.hasRunningAnimationDriver()
                && stoppedAnimationPortal.animation.lastTickAnimatedState==null
                && stoppedAnimationPortal.animation.thisTickAnimatedState==null;
            stoppedAnimationDrainedTicks=drained?stoppedAnimationDrainedTicks+1:0;
            if (stoppedAnimationDrainedTicks<4) return;
            require(current.getGameTime()-stoppedAnimationStartTick>=23,"animation did not run before delayed request");
            require(stoppedAnimationCrossedTick>=0 && current.getGameTime()-stoppedAnimationCrossedTick>=3,
                "source plane did not actually sweep the held seated eye before stopping");
            require(sourcePlaneSpeed==0,"source plane is still moving at delayed request");
            // Eliminate instantaneous approach as an alternative success path. Native writes
            // here belong only to this explicitly held compatibility probe, never flight.
            setVelocity(body,new Vector3d());
            double linearSpeed=handle(body).getLinearVelocity(new Vector3d()).length();
            double angularSpeed=handle(body).getAngularVelocity(new Vector3d()).length();
            require(linearSpeed==0 && angularSpeed==0,
                "delayed animation witness requires zero current native motion");
            recordStoppedAnimationWitness("before-delayed-request");
            var manager=qouteall.imm_ptl.core.teleportation.ServerTeleportationManager.of(player.server);
            require(!manager.isJustTeleported(player,1),"stopped-animation request needs a quiet request interval");
            manager.onPlayerTeleportedInClient(player,current.dimension(),stoppedAnimationEye,stoppedAnimationPortal.getUUID());
            require(manager.isJustTeleported(player,1),"stopped-animation request did not resolve normal portal entry point");
            require(SubLevelContainer.getContainer(source).getSubLevel(fixture.id())==null,
                "stopped-animation delayed request failed to migrate the complete body");
            current=destination;
            var arrived=fixture.requireBody(current);
            require(arrived!=body,"stopped-animation request did not create destination ownership");
            verifyRider(arrived);
            var integrity=fixture.verifyIntegrity(current,arrived);
            hover=new Vector3d(arrived.logicalPose().position()); hold(arrived);
            stoppedAnimationReceipt=new LinkedHashMap<>();
            stoppedAnimationReceipt.put("kind","held_body_stopped_source_animation");
            stoppedAnimationReceipt.put("counted_as_flight_crossing",false);
            stoppedAnimationReceipt.put("driver","NormalAnimation");
            stoppedAnimationReceipt.put("animation_ticks",20);
            stoppedAnimationReceipt.put("drained_ticks_before_request",stoppedAnimationDrainedTicks);
            stoppedAnimationReceipt.put("ticks_after_sweep",source.getGameTime()-stoppedAnimationCrossedTick);
            stoppedAnimationReceipt.put("ship_id",fixture.id().toString()); stoppedAnimationReceipt.put("seat_id",seatId.toString());
            stoppedAnimationReceipt.put("from",source.dimension().location().toString());
            stoppedAnimationReceipt.put("to",destination.dimension().location().toString());
            stoppedAnimationReceipt.put("native_linear_speed_at_request",linearSpeed);
            stoppedAnimationReceipt.put("native_angular_speed_at_request",angularSpeed);
            stoppedAnimationReceipt.put("source_plane_speed_at_request",sourcePlaneSpeed);
            stoppedAnimationReceipt.put("zero_native_motion_at_request",true);
            stoppedAnimationReceipt.put("request_resolved",true);
            stoppedAnimationReceipt.put("unique_body",true); stoppedAnimationReceipt.put("rider_graph",true);
            stoppedAnimationReceipt.put("integrity_snapshot",integrity);
            stoppedAnimationStage="verify"; writeStoppedAnimationControl("verify");
            return;
        }
        if (stoppedAnimationStage.equals("verify")) {
            verifyRider(body);
            if (!ack.equals(stoppedAnimationToken()+":arrived")) return;
            stoppedAnimationReceipt.put("client_ack",true);
            stoppedAnimationStage="resume"; writeStoppedAnimationControl("resume");
            return;
        }
        require(stoppedAnimationStage.equals("resume"),"unknown stopped-animation probe phase");
        if (!ack.equals(stoppedAnimationToken()+":resumed")) return;
        stoppedAnimationReceipt.put("prediction_restored",true);
        caseReport.put("stopped_animation_witness",stoppedAnimationReceipt);
        PortalSmokeSupport.write("stopped-animation-"+caseIndex+".json",JSON.toJson(stoppedAnimationReceipt));
        writeStoppedAnimationControl("idle");
        stoppedAnimationPortal.discard(); stoppedAnimationPortal=null; stoppedAnimationStage=null;
        finishCase();
    }
    private static void recordStoppedAnimationWitness(String stage) {
        var sample=new LinkedHashMap<>(qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat
            .describeAnimatedRiderWitness(player,stoppedAnimationPortal));
        sample.put("stage",stage);
        stoppedAnimationTrace.add(sample);
        PortalSmokeSupport.write("stopped-animation-witness-"+caseIndex+".json",JSON.toJson(stoppedAnimationTrace));
    }
    private static void fly() {
        flightTicks++;
        ServerLevel target = leg % 2 == 0 ? destination : source;
        var arriving = SubLevelContainer.getContainer(target).getSubLevel(fixture.id());
        if (!(arriving instanceof ServerSubLevel body)) {
            var active = fixture.requireBody(current);
            if (broadside()) recordBroadsideSample(active, false);
            if (aeronautics()) {
                int direction=leg%2==0?-1:1;
                fixture.drive().verifyThrust(current,active,-direction*256); forceSamples++;
                double acceleration=(handle(active).getLinearVelocity(new Vector3d()).y-previousVelocity.y)*20;
                peakSignedAcceleration=Math.max(peakSignedAcceleration,direction*acceleration);
                if (flightTicks>=10) poweredMotion=fixture.drive().verifyPoweredMotion(poweredStart,active,direction);
            }
            observedFlightDistance = Math.max(observedFlightDistance, launch.distance(active.logicalPose().position()));
            previousVelocity = handle(active).getLinearVelocity(new Vector3d());
            previousOrientation = new Quaterniond(active.logicalPose().orientation());
            previousPose = new Pose3d(active.logicalPose());
            require(phaseTicks < 400, "native ship never crossed portal");
            return;
        }
        require(SubLevelContainer.getContainer(current).getSubLevel(fixture.id()) == null, "duplicate source body after native crossing");
        Portal portal = leg % 2 == 0 ? forward : reverse;
        Vec3 expected = portal.transformLocalVec(new Vec3(previousVelocity.x,previousVelocity.y,previousVelocity.z));
        Vector3d actual = handle(body).getLinearVelocity(new Vector3d());
        writeSeamDiagnostics(portal,body,expected,actual);
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
        if (broadside()) recordBroadsideSample(body, true);
        var integrity=fixture.verifyIntegrity(current, body); verifyRider(body);
        crossing = new LinkedHashMap<>();
        crossing.put("leg",leg); crossing.put("from",portal.level().dimension().location().toString());
        crossing.put("to",current.dimension().location().toString());
        crossing.put("native_motion_ticks",flightTicks); crossing.put("native_distance",distance);
        crossing.put("unique_body",true); crossing.put("rider_graph",true); crossing.put("integrity",true);
        crossing.put("velocity",true); crossing.put("orientation",true);
        crossing.put("arrival_speed",actual.length());
        crossing.put("integrity_snapshot",integrity);
        if (aeronautics()) {
            require(forceSamples>=5 && poweredMotion!=null && peakSignedAcceleration>.05,"no genuine powered-motion witness");
            require(leg%2!=0 || peakSignedAcceleration>-flightGravityY+1,
                "downhill interval is indistinguishable from gravity alone; propeller actor may not be applying force");
            crossing.put("powered_thrust",true); crossing.put("no_native_velocity_writes",true);
            crossing.put("propeller_count",fixture.drive().engineCount()); crossing.put("force_samples",forceSamples);
            crossing.put("peak_vertical_acceleration",(leg%2==0?-1:1)*peakSignedAcceleration);
            crossing.put("gravity_y",flightGravityY);
            crossing.put("powered_motion",poweredMotion);
        }
        phase(Phase.CLEAR_SEAM);
    }
    private static void clearSeam() {
        var body = fixture.requireBody(current);
        Portal portal = leg % 2 == 0 ? reverse : forward;
        require(phaseTicks < 300,"ship failed to clear seam");
        if (broadside()) {
            var bounds=recordBroadsideSample(body,true);
            if (bounds.maxNormal() > -SableShipBroadsideGeometry.CLEAR_GAP) return;
            require(broadsideStages.keySet().equals(new java.util.LinkedHashSet<>(List.of("leading","center","trailing"))),
                "broadside flight did not sample all three hull passage stages: "+broadsideStages.keySet());
            var receipt=new LinkedHashMap<String,Object>();
            receipt.put("launch",broadsideLaunch); receipt.put("samples",List.copyOf(broadsideSamples));
            receipt.put("stages",new LinkedHashMap<>(broadsideStages));
            receipt.put("final_max_normal",bounds.maxNormal()); receipt.put("clearance_gap",SableShipBroadsideGeometry.CLEAR_GAP);
            receipt.put("whole_hull_cleared",true); receipt.put("aperture_fit",true);
            receipt.put("no_native_writes_during_flight",true);
            crossing.put("broadside_receipt",receipt);
            PortalSmokeSupport.write("broadside-"+caseIndex+"-"+leg+".json",JSON.toJson(receipt));
            if (leg==1) require(Math.abs(body.logicalPose().orientation().dot(new Quaterniond()))>.999,
                "broadside return failed to restore original ship orientation");
            broadsideInFlight=false;
        } else {
            double clearance = Math.abs(body.logicalPose().position().y() - portal.getOriginPos().y);
            if (clearance < 28) return;
        }
        hover = new Vector3d(body.logicalPose().position()); hold(body);
        fixture.setMotorSpeed(current,body,rpm()); fixture.mutateProbe(current,body,mutated()); prepareDrive(body);
        phase(Phase.SETTLE);
    }
    private static void launchBroadside(ServerSubLevel body) {
        Portal entry=leg%2==0 ? forward : reverse;
        require(Math.abs(entry.getNormal().y)<1e-8,"broadside portal is not vertical");
        if (leg==0) require(Math.abs(body.logicalPose().orientation().dot(new Quaterniond()))>.999,
            "broadside fixture was rotated nose-first before flight");
        var bounds=broadsideBounds(body,false);
        require(bounds.minNormal()>=SableShipBroadsideGeometry.CLEAR_GAP-1,
            "broadside launch began with part of the hull in the seam: "+bounds);
        var gravity=DimensionPhysicsData.getGravity(current,body.logicalPose().position());
        var otherGravity=DimensionPhysicsData.getGravity(leg%2==0 ? destination : source);
        double drag=DimensionPhysicsData.getUniversalDrag(current);
        require(gravity.isFinite() && gravity.y<0 && Math.abs(gravity.x)+Math.abs(gravity.z)<1e-8
            && gravity.distance(otherGravity)<1e-8,"broadside ballistic plan needs matching native vertical gravity");
        require(Math.abs(drag-DimensionPhysicsData.getUniversalDrag(leg%2==0 ? destination : source))<1e-8,
            "broadside ballistic plan needs matching native damping");
        var plan=SableShipBroadsideGeometry.launch(bounds.maxNormal()+SableShipBroadsideGeometry.CLEAR_GAP,gravity.y,drag);
        require(plan.upwardSpeed()<=SableShipBroadsideGeometry.SPEED,
            "gravity compensation would dominate the sideways launch");
        var hull=fixtureWorldBounds(body,body.logicalPose(),null);
        require(hull.maxY+plan.maxRise()<SableShipFlightCorridor.MAX_Y,
            "predicted broadside arc exceeds the prepared corridor");
        Vec3 horizontal=entry.getNormal().scale(-SableShipBroadsideGeometry.SPEED);
        Vector3d velocity=new Vector3d(horizontal.x,plan.upwardSpeed(),horizontal.z);
        setVelocity(body,new Vector3d(velocity));
        Vector3d actualVelocity=handle(body).getLinearVelocity(new Vector3d());
        require(actualVelocity.isFinite() && actualVelocity.distance(velocity)<1e-6,
            "native broadside launch did not apply the planned absolute velocity");
        broadsideSamples.clear(); broadsideStages.clear(); broadsideTicks=0;
        broadsideLaunch=new LinkedHashMap<>();
        broadsideLaunch.put("kind","native-ballistic");
        broadsideLaunch.put("horizontal_speed",SableShipBroadsideGeometry.SPEED);
        broadsideLaunch.put("gravity_y",gravity.y); broadsideLaunch.put("universal_drag",drag);
        broadsideLaunch.put("planned_distance",plan.distance()); broadsideLaunch.put("planned_duration",plan.duration());
        broadsideLaunch.put("upward_speed",plan.upwardSpeed()); broadsideLaunch.put("predicted_max_rise",plan.maxRise());
        broadsideLaunch.put("initial_velocity",List.of(actualVelocity.x,actualVelocity.y,actualVelocity.z));
        broadsideLaunch.put("initial_bounds",describeBroadsideBounds(bounds));
        broadsideInFlight=true;
        recordBroadsideSample(body,false);
    }
    private static SableShipBroadsideGeometry.Bounds broadsideBounds(ServerSubLevel body,boolean arrived) {
        Portal entry=leg%2==0 ? forward : reverse;
        var center=body.getPlot().getCenterBlock(); var layout=fixture.layout();
        var corners=new SableShipBroadsideGeometry.Point[8]; int i=0;
        for (int x:new int[]{-layout.halfWidth(),layout.halfWidth()+1})
            for (int y:new int[]{0,layout.height()}) for (int z:new int[]{-layout.halfLength(),layout.halfLength()+1}) {
                Vec3 world=body.logicalPose().transformPosition(new Vec3(center.getX()+x,center.getY()+y,center.getZ()+z));
                // Normalize destination corners back into the same entry frame. This is
                // a measurement only; the native body/pose is never changed by the test.
                if (arrived) world=entry.inverseTransformPoint(world);
                Vec3 local=entry.transformFromWorldToPortalLocal(world);
                corners[i++]=new SableShipBroadsideGeometry.Point(local.x,local.y,local.z);
            }
        return SableShipBroadsideGeometry.bounds(corners);
    }
    private static Map<String,Object> describeBroadsideBounds(SableShipBroadsideGeometry.Bounds b) {
        return Map.of("min_normal",b.minNormal(),"center_normal",b.centerNormal(),"max_normal",b.maxNormal(),
            "min_width",b.minWidth(),"max_width",b.maxWidth(),"min_height",b.minHeight(),"max_height",b.maxHeight());
    }
    /** Read-only native samples through leading, center and trailing passage, before stationkeeping resumes. */
    private static SableShipBroadsideGeometry.Bounds recordBroadsideSample(ServerSubLevel body,boolean arrived) {
        var bounds=broadsideBounds(body,arrived);
        require(bounds.fitsAperture(),"actual swept broadside corner escaped aperture: "+bounds);
        var worldVelocity=handle(body).getLinearVelocity(new Vector3d());
        var localVelocity=new Quaterniond(body.logicalPose().orientation()).conjugate().transform(worldVelocity,new Vector3d());
        var nose=new Quaterniond(body.logicalPose().orientation()).transform(new Vector3d(0,0,1));
        double cosine=Math.abs(nose.dot(worldVelocity))/worldVelocity.length();
        require(Double.isFinite(cosine) && cosine<.025 && localVelocity.x*(leg%2==0 ? -1 : 1)>8
            && Math.abs(localVelocity.z)<Math.abs(localVelocity.x)*.025,
            "native motion is not broadside to the ship nose: local="+localVelocity+" cosine="+cosine);
        verifyRider(body);
        AABB hull=fixtureWorldBounds(body,body.logicalPose(),null);
        require(
            SableShipFlightCorridor.contains(hull.minX,hull.minY,hull.minZ)
                && SableShipFlightCorridor.contains(hull.maxX,hull.maxY,hull.maxZ),
            "native broadside hull escaped prepared terrain: "+hull);
        var sample=new LinkedHashMap<String,Object>();
        sample.put("sample",broadsideTicks++); sample.put("dimension",current.dimension().location().toString());
        sample.put("native_tick",current.getGameTime()); sample.put("arrived",arrived);
        sample.put("ship_id",fixture.id().toString()); sample.put("bounds",describeBroadsideBounds(bounds));
        sample.put("world_bounds",describeBounds(hull)); sample.put("pose",describePose(body.logicalPose()));
        sample.put("native_velocity",List.of(worldVelocity.x,worldVelocity.y,worldVelocity.z));
        sample.put("body_local_velocity",List.of(localVelocity.x,localVelocity.y,localVelocity.z));
        sample.put("nose_world",List.of(nose.x,nose.y,nose.z)); sample.put("nose_velocity_abs_cosine",cosine);
        @SuppressWarnings("unchecked") var initialBounds=(Map<String,Object>)broadsideLaunch.get("initial_bounds");
        sample.put("native_distance",((Number)initialBounds.get("center_normal")).doubleValue()-bounds.centerNormal());
        sample.put("source_body_count",SubLevelContainer.getContainer(source).getAllSubLevels().size());
        sample.put("destination_body_count",SubLevelContainer.getContainer(destination).getAllSubLevels().size());
        sample.put("seat_id",seatId.toString()); sample.put("player_id",player.getUUID().toString());
        sample.put("player_dimension",player.serverLevel().dimension().location().toString());
        sample.put("player_vehicle_id",player.getVehicle().getUUID().toString());
        sample.put("seat_passenger_ids",current.getEntity(seatId).getPassengers().stream().map(entity->entity.getUUID().toString()).toList());
        sample.put("unique_body",true); sample.put("rider_graph",true); sample.put("aperture_fit",true);
        broadsideSamples.add(sample);
        if (bounds.leading() && !broadsideStages.containsKey("leading")) recordBroadsideStage("leading",sample,body);
        if (bounds.center() && !broadsideStages.containsKey("center")) recordBroadsideStage("center",sample,body);
        if (bounds.trailing() && !broadsideStages.containsKey("trailing")) recordBroadsideStage("trailing",sample,body);
        PortalSmokeSupport.write("broadside-progress-"+caseIndex+"-"+leg+".json",JSON.toJson(Map.of(
            "launch",broadsideLaunch,"samples",broadsideSamples,"stages",broadsideStages)));
        return bounds;
    }
    private static void recordBroadsideStage(String stage,Map<String,Object> sample,ServerSubLevel body) {
        if (!broadsideStages.isEmpty()) {
            var previous=broadsideStages.values().stream().reduce((a,b)->b).orElseThrow();
            require(((Number)sample.get("sample")).intValue()>((Number)previous.get("sample")).intValue(),
                "broadside passage skipped a separately observed hull stage");
        }
        var receipt=new LinkedHashMap<>(sample);
        receipt.put("integrity_snapshot",fixture.verifyIntegrity(current,body));
        receipt.put("machinery_snapshot",fixture.verifyMachinery(current,body,leg%2==0 ? 16 : -32));
        broadsideStages.put(stage,receipt);
    }
    private static void writeSeamDiagnostics(Portal portal,ServerSubLevel body,Vec3 expected,Vector3d actual) {
        var report=new LinkedHashMap<String,Object>();
        report.put("case_index",caseIndex); report.put("leg",leg); report.put("flight_ticks",flightTicks);
        report.put("source",current.dimension().location().toString());
        report.put("destination",body.getLevel().dimension().location().toString());
        report.put("source_tick",current.getGameTime()); report.put("destination_tick",body.getLevel().getGameTime());
        report.put("previous_source_pose",describePose(previousPose));
        report.put("actual_destination_pose",describePose(body.logicalPose()));
        Vec3 transformedPosition=portal.transformPoint(new Vec3(previousPose.position().x(),previousPose.position().y(),previousPose.position().z()));
        report.put("expected_transformed_previous_position",List.of(transformedPosition.x,transformedPosition.y,transformedPosition.z));
        var transformedOrientation=new Quaterniond(previousPose.orientation());
        var rotation=portal.getRotation();
        if (rotation!=null) transformedOrientation.premul(new Quaterniond(rotation.x,rotation.y,rotation.z,rotation.w));
        report.put("expected_transformed_previous_orientation",List.of(transformedOrientation.x,transformedOrientation.y,transformedOrientation.z,transformedOrientation.w));
        report.put("previous_source_velocity",List.of(previousVelocity.x,previousVelocity.y,previousVelocity.z));
        report.put("expected_velocity",List.of(expected.x,expected.y,expected.z));
        report.put("actual_velocity",List.of(actual.x,actual.y,actual.z));
        report.put("velocity_error",actual.distance(new Vector3d(expected.x,expected.y,expected.z)));
        AABB actualBounds=fixtureWorldBounds(body,body.logicalPose(),null);
        report.put("actual_hull_aabb",describeBounds(actualBounds));
        report.put("expected_transformed_previous_hull_aabb",describeBounds(fixtureWorldBounds(body,previousPose,portal)));
        if ((aeronautics() || broadside()) && body.getLevel()==destination) report.put("hull_inside_prepared_corridor",
            SableShipFlightCorridor.contains(actualBounds.minX,actualBounds.minY,actualBounds.minZ)
                && SableShipFlightCorridor.contains(actualBounds.maxX,actualBounds.maxY,actualBounds.maxZ));
        report.put("terrain_collision_candidates",terrainCollisionCandidates(body.getLevel(),actualBounds));
        PortalSmokeSupport.write("seam-"+caseIndex+"-"+leg+".json",JSON.toJson(report));
    }
    private static Map<String,Object> describePose(Pose3dc pose) {
        return Map.of("position",List.of(pose.position().x(),pose.position().y(),pose.position().z()),
            "orientation",List.of(pose.orientation().x(),pose.orientation().y(),pose.orientation().z(),pose.orientation().w()),
            "rotation_point",List.of(pose.rotationPoint().x(),pose.rotationPoint().y(),pose.rotationPoint().z()),
            "scale",List.of(pose.scale().x(),pose.scale().y(),pose.scale().z()));
    }
    private static List<Double> describeBounds(AABB bounds) {
        return List.of(bounds.minX,bounds.minY,bounds.minZ,bounds.maxX,bounds.maxY,bounds.maxZ);
    }
    private static AABB fixtureWorldBounds(ServerSubLevel body,Pose3dc pose,Portal transform) {
        var center=body.getPlot().getCenterBlock(); var layout=fixture.layout();
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for (int x:new int[]{-layout.halfWidth(),layout.halfWidth()+1})
            for (int y:new int[]{0,layout.height()}) for (int z:new int[]{-layout.halfLength(),layout.halfLength()+1}) {
                Vec3 point=pose.transformPosition(new Vec3(center.getX()+x,center.getY()+y,center.getZ()+z));
                if (transform!=null) point=transform.transformPoint(point);
                minX=Math.min(minX,point.x); minY=Math.min(minY,point.y); minZ=Math.min(minZ,point.z);
                maxX=Math.max(maxX,point.x); maxY=Math.max(maxY,point.y); maxZ=Math.max(maxZ,point.z);
            }
        return new AABB(minX,minY,minZ,maxX,maxY,maxZ);
    }
    /** Read-only broad-phase candidates, not native contact manifolds or proof of a hull collision. */
    private static Map<String,Object> terrainCollisionCandidates(ServerLevel level,AABB bounds) {
        int minX=(int)Math.floor(bounds.minX),minY=(int)Math.floor(bounds.minY),minZ=(int)Math.floor(bounds.minZ);
        int maxX=(int)Math.ceil(bounds.maxX)-1,maxY=(int)Math.ceil(bounds.maxY)-1,maxZ=(int)Math.ceil(bounds.maxZ)-1;
        long volume=(long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1);
        int visited=0,unknown=0,nonair=0,colliders=0;
        var examples=new ArrayList<Map<String,Object>>(); var position=new BlockPos.MutableBlockPos();
        scan: for (int cx=minX>>4;cx<=maxX>>4;cx++) for (int cz=minZ>>4;cz<=maxZ>>4;cz++) {
            var chunk=level.getChunkSource().getChunk(cx,cz,ChunkStatus.FULL,false);
            for (int x=Math.max(minX,cx<<4);x<=Math.min(maxX,(cx<<4)+15);x++)
                for (int z=Math.max(minZ,cz<<4);z<=Math.min(maxZ,(cz<<4)+15);z++) for (int y=minY;y<=maxY;y++) {
                    if (visited>=262_144) break scan;
                    visited++;
                    if (chunk==null) { unknown++; continue; }
                    position.set(x,y,z); var state=chunk.getBlockState(position);
                    if (state.isAir()) continue;
                    nonair++;
                    // Stored state only: dynamic shape queries can consult neighboring
                    // world chunks and implicitly generate terrain during this diagnostic.
                    if (state.blocksMotion()) {
                        colliders++;
                        if (examples.size()<16) examples.add(Map.of("position",List.of(x,y,z),"state",state.toString()));
                    }
                }
        }
        return Map.of("aabb_positions",volume,"visited_positions",visited,"unknown_chunk_positions",unknown,
            "nonair_blocks",nonair,"motion_blocking_blocks",colliders,"examples",examples,
            "complete",visited==volume && unknown==0,"position_limit",262_144,
            "meaning","Stored motion-blocking terrain states inside the hull AABB; not exact collision shapes or native contacts, and empty hull space or rotation can produce false positives");
    }
    private static void verifyRider(ServerSubLevel body) {
        // Disposable worlds contain only this fixture: also reject a clone with a new UUID.
        int bodies=SubLevelContainer.getContainer(source).getAllSubLevels().size()
            +SubLevelContainer.getContainer(destination).getAllSubLevels().size();
        require(bodies==1,"unexpected extra or missing native bodies: "+bodies);
        Entity seat = current.getEntity(seatId);
        require(seat != null && Sable.HELPER.getContaining(seat) == body,"retained seat missing from ship: " + riderDiagnostics(body));
        require(player.serverLevel() == current && player.getVehicle() == seat && seat.getPassengers().contains(player),"rider separated from vessel");
        ServerLevel other = current == source ? destination : source;
        require(other.getEntity(seatId) == null,"duplicated retained seat");
    }
    private static String riderDiagnostics(ServerSubLevel body) {
        Entity here=current.getEntity(seatId);
        Entity there=(current==source?destination:source).getEntity(seatId);
        return "seat="+seatId+" here="+here+" other="+there+" body="+fixture.id()
            +" bodyPose="+body.logicalPose().position()+" player="+player.position()
            +" playerDimension="+player.serverLevel().dimension().location()+" vehicle="+player.getVehicle()
            +" seatContaining="+(here==null?null:Sable.HELPER.getContaining(here));
    }
    private static void checkpoint(String status, String failure) {
        var report=new LinkedHashMap<String,Object>();
        report.put("status",status); report.put("failure",failure); report.put("case_index",caseIndex);
        report.put("leg",leg); report.put("phase",phase.name()); report.put("completed_cases",List.copyOf(cases));
        if (caseReport!=null) { var active=new LinkedHashMap<>(caseReport);
            active.put("crossings",List.copyOf(crossings)); report.put("active_case",active); }
        PortalSmokeSupport.write("server-progress.json",JSON.toJson(report));
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
    private static void setVelocity(ServerSubLevel body, Vector3dc velocity) {
        require(!broadsideInFlight,"native velocity write attempted during broadside flight");
        var handle = handle(body);
        // Preserve the requested absolute velocity for launch receipts/callers. Native
        // physics consumes a delta, which must have its own independently mutable vector.
        handle.addLinearAndAngularVelocity(new Vector3d(velocity).sub(handle.getLinearVelocity(new Vector3d())),
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
