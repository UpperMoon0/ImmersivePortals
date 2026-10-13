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

/** Real assembly and native-physics flight. No direct ship transfer; a separate stale-request negative regression must not migrate anything. */
public final class SableShipFlightTest {
    private enum Phase { LOGIN, ASSEMBLY, SETTLE, OBSERVE, FLY, CLEAR_SEAM, STOPPED_ANIMATION, DONE }
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
    private static Map<String, Object> caseReport, crossing;
    private static final List<Map<String, Object>> cases = new ArrayList<>();
    private static final List<Map<String, Object>> crossings = new ArrayList<>();
    private static final List<Map<String, Object>> stoppedAnimationTrace = new ArrayList<>();
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
    static int expectedCases() { return (selectedSize().equals("all") ? 2 : 1) * (aeronautics() ? 1 : 2); }
    private static int lastCaseExclusive() { return selectedSize().equals("control") ? 2 : 4; }
    static boolean enabled() { return "true".equalsIgnoreCase(System.getenv("IP_SABLE_SHIPS")); }
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer joined)) return;
        require(List.of("all", "control", "large").contains(selectedSize()), "invalid ship size filter");
        caseIndex = selectedSize().equals("large") ? 2 : 0;
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
        if (aeronautics()) {
            // Natural atmospheric pressure is required for real propellers; the baseline high-altitude route is a vacuum.
            for (int x=-48;x<=48;x++) for (int z=-48;z<=48;z++) for (int clearY=0;clearY<=80;clearY++) {
                BlockPos position=new BlockPos(x,clearY,z);
                if (!destination.getBlockState(position).isAir()) destination.setBlock(position,Blocks.AIR.defaultBlockState(),2);
            }
        }
        int y = stack() ? source.getMinBuildHeight() + 40 : aeronautics() ? 32 : 160;
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
            forward.setOriginPos(new Vec3(0, aeronautics() ? -8 : 120, 0));
            // X cross -Z gives +Y, so the vessel approaches the front while descending.
            forward.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 0, -1), 160, 160);
            forward.setDestinationDimension(Level.NETHER);
            forward.setDestination(new Vec3(0, aeronautics() ? 48 : 224, 0));
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
        caseReport.put("propulsion", aeronautics() ? "real Aeronautics propeller actors; no native velocity writes during powered intervals" : "native rigid-body velocity launch; not an Aeronautics engine acceptance test");
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
        if (leg == 3) {
            caseReport.put("crossings", List.copyOf(crossings));
            if (!stack()) {
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
        } else setVelocity(body, leg % 2 == 0 ? new Vector3d(8, -SPEED, 4) : new Vector3d(-4, SPEED, 8));
        previousVelocity = handle(body).getLinearVelocity(new Vector3d());
        previousOrientation = new Quaterniond(body.logicalPose().orientation());
        phase(Phase.FLY);
    }
    private static void finishCase() {
        cases.add(caseReport);
        cleanup(); caseIndex += aeronautics() ? 2 : 1;
        if (caseIndex == lastCaseExclusive()) {
            var report = new LinkedHashMap<String,Object>(); report.put("schema_version",1); report.put("cases",cases);
            if (aeronautics()) report.put("addons",addonVersions());
            PortalSmokeSupport.write("server-ships.json", JSON.toJson(report));
            PortalSmokeSupport.write("server-pass.txt", expectedCases() + " assembled ship cases completed " + (expectedCases()*4) + " native crossings with rider/cargo/machinery continuity; aeronautics=" + aeronautics());
            phase(Phase.DONE);
        } else setup();
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
        double clearance = Math.abs(body.logicalPose().position().y() - portal.getOriginPos().y);
        require(phaseTicks < 300,"ship failed to clear seam");
        if (clearance < 28) return;
        hover = new Vector3d(body.logicalPose().position()); hold(body);
        fixture.setMotorSpeed(current,body,rpm()); fixture.mutateProbe(current,body,mutated()); prepareDrive(body);
        phase(Phase.SETTLE);
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
