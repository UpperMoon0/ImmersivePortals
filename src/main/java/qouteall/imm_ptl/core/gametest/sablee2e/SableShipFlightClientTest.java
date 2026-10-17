package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Graphical client: full multi-chunk payload, live kinetic updates, rider ownership and pixel-state transitions. */
public final class SableShipFlightClientTest {
    private static boolean connecting, done;
    private static final Map<String,SableAeronauticsShipDrive> drives=new LinkedHashMap<>();
    private static String observedToken = "";
    private static int ticks, stable;
    private static String pendingToken = "";
    private static String overviewToken = "";
    private static int overviewTicks;
    private static net.minecraft.world.entity.Entity overviewCamera;
    private static net.minecraft.world.entity.Entity panelCamera;
    private static boolean stoppedAnimationPredictionPaused, previousDisableTeleportation;
    private static final Map<String,Map<String,Object>> cases = new LinkedHashMap<>();
    private static final Map<String,List<Map<String,Object>>> crossings = new LinkedHashMap<>();
    private static final Map<String,List<String>> screenshots = new LinkedHashMap<>();
    private static final Map<String,Map<String,Object>> overviewMeshes = new LinkedHashMap<>();

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!SableShipFlightTest.enabled() || done) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++ticks > 20000) throw new IllegalStateException("graphical ship test timeout, token=" + pendingToken);
            if (mc.player == null || mc.level == null) {
                restoreStoppedAnimationPrediction();
                if (!connecting && mc.screen != null) {
                    connecting = true;
                    String address = "127.0.0.1:" + System.getenv().getOrDefault("IP_SABLE_E2E_PORT","25565");
                    ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(address),
                        new ServerData("Complex Sable ship test",address,ServerData.Type.OTHER),true,null);
                }
                return;
            }
            if (handleStoppedAnimationProbe(mc)) return;
            if (PortalSmokeSupport.exists("server-pass.txt")) {
                require(cases.size() == SableShipFlightTest.expectedCases(), "server completed before expected client cases");
                for (String key : cases.keySet()) {
                    int expectedCrossings=SableShipFlightTest.expectedCrossingsForRoute((String)cases.get(key).get("route"));
                    require(crossings.get(key).size() == expectedCrossings,"missing client crossings for " + key);
                    cases.get(key).put("crossings",crossings.get(key));
                    cases.get(key).put("screenshots",screenshots.get(key));
                    cases.get(key).put("snapshots",screenshots.get(key).size());
                }
                var report=new LinkedHashMap<String,Object>(); report.put("schema_version",1); report.put("cases",cases.values());
                if (SableShipFlightTest.aeronautics()) report.put("addons",SableShipFlightTest.addonVersions());
                PortalSmokeSupport.write("client-ships.json",new Gson().toJson(report));
                PortalSmokeSupport.write(SableStandingBalloonServer.afterShips() ? "ships-client-pass.txt" : "client-pass.txt","Real client observed " + SableShipFlightTest.expectedCrossings() + " assembled-ship crossings, live block/kinetic mutations and contrasting framebuffer states");
                restoreStoppedAnimationPrediction(); restorePlayerCamera(mc); done = true;
                if (!SableStandingBalloonServer.afterShips()) mc.stop();
                return;
            }
            String text = PortalSmokeSupport.read("ship-observe.json");
            if (text.isBlank()) return;
            JsonObject request = JsonParser.parseString(text).getAsJsonObject();
            String token = request.get("token").getAsString();
            if (token.equals(observedToken)) return;
            if (!token.equals(pendingToken)) { pendingToken = token; stable = 0; }
            if (!mc.level.dimension().location().toString().equals(request.get("dimension").getAsString())) { waiting("dimension="+mc.level.dimension().location()); return; }
            UUID id = UUID.fromString(request.get("ship_id").getAsString());
            var container = SubLevelContainer.getContainer(mc.level);
            var rawBody = container == null ? null : container.getSubLevel(id);
            if (!(rawBody instanceof ClientSubLevel body)) { waiting("body missing id="+id+" dimension="+mc.level.dimension().location()); return; }
            var seat = mc.player.getVehicle();
            if (seat == null || !seat.getUUID().toString().equals(request.get("seat_id").getAsString())
                || !seat.getPassengers().contains(mc.player) || Sable.HELPER.getContaining(seat) != body) { waiting("rider seat="+seat+" expected="+request.get("seat_id")+" containing="+(seat==null?null:Sable.HELPER.getContaining(seat))); return; }
            var otherDimension = mc.level.dimension().equals(Level.OVERWORLD) ? Level.NETHER : Level.OVERWORLD;
            if (ClientWorldLoader.getServerDimensions().contains(otherDimension)) {
                var other = SubLevelContainer.getContainer(ClientWorldLoader.getWorld(otherDimension));
                if (other != null && other.getSubLevel(id) != null) { waiting("stale body in other dimension"); return; }
            }
            BlockPos center = body.getPlot().getCenterBlock();
            for (var item : request.getAsJsonArray("blocks_manifest")) {
                var row = item.getAsJsonArray();
                var position = center.offset(row.get(0).getAsInt(),row.get(1).getAsInt(),row.get(2).getAsInt());
                if (Block.getId(mc.level.getBlockState(position)) != row.get(3).getAsInt()) { waiting("block mismatch relative="+row+" actual="+mc.level.getBlockState(position)+" actual_id="+Block.getId(mc.level.getBlockState(position))); return; }
            }
            for (var item : request.getAsJsonArray("kinetics")) {
                var row = item.getAsJsonArray();
                var be = mc.level.getBlockEntity(center.offset(row.get(0).getAsInt(),row.get(1).getAsInt(),row.get(2).getAsInt()));
                if (!(be instanceof KineticBlockEntity kinetic) || !Float.isFinite(kinetic.getSpeed()) || Math.abs(kinetic.getSpeed()-row.get(3).getAsFloat())>.01) { waiting("kinetic mismatch relative="+row+" actual="+(be instanceof KineticBlockEntity k?k.getSpeed():be)); return; }
            }
            if (SableShipFlightTest.aeronautics()) {
                String size=request.get("size").getAsString();
                var drive=drives.computeIfAbsent(size, key -> SableAeronauticsShipDrive.create(key.equals("LARGE")));
                try { drive.verifyThrust(mc.level,body,request.get("propeller_rpm").getAsInt()); }
                catch (IllegalStateException pending) { waiting("propeller update: "+pending.getMessage()); return; }
            }
            // An independent client-only camera preserves the real player/seat graph while documenting the whole vessel.
            if (!overviewToken.equals(token)) {
                if (overviewCamera==null) {
                    writeRenderDiagnostics(mc,body,token,"before-overview");
                    overviewCamera=net.minecraft.world.entity.EntityType.ARMOR_STAND.create(mc.level);
                    require(overviewCamera!=null,"cannot create client overview camera");
                    overviewTicks=0;
                }
                var dimensions=request.getAsJsonArray("dimensions");
                double width=dimensions.get(0).getAsDouble(), height=dimensions.get(1).getAsDouble(), length=dimensions.get(2).getAsDouble();
                boolean broadsideOutbound=request.get("route").getAsString().equals("vertical-broadside")
                    && request.get("leg").getAsInt()==0;
                // Pose3d transforms absolute hidden-plot positions, not relative fixture offsets.
                var lowView=broadsideOutbound
                    ? SableShipObservationCamera.overviewAfterBroadsideCrossing(width,height,length)
                    : SableShipObservationCamera.overviewAfterCrossing(width,height,length);
                var view=request.get("leg").getAsInt()<0
                    ? SableShipObservationCamera.overview(width,height,length)
                    : lowView;
                var eye=shipPoint(body,center,view.eye());
                // CrossPortalViewRendering traces this same rider-to-camera segment. Never
                // certify another world's remapped camera as a view of the current body.
                var cameraPortal=overviewCameraPortal(mc,eye);
                if (cameraPortal!=null) {
                    view=lowView;
                    eye=shipPoint(body,center,view.eye());
                    cameraPortal=overviewCameraPortal(mc,eye);
                }
                aimCamera(mc,overviewCamera,eye,shipPoint(body,center,view.target()));
                if (++overviewTicks<25) return;
                var mesh=visibleStaticMesh(mc,body,request,view,cameraPortal);
                if (!Boolean.TRUE.equals(mesh.get("complete"))) {
                    if (overviewTicks==25 || overviewTicks % 60==0) {
                        writeRenderDiagnostics(mc,body,token,"overview-pending");
                        PortalSmokeSupport.write("mesh-pending-"+token.replace(':','-')+".json",new Gson().toJson(mesh));
                        try (NativeImage overview=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                            overview.writeToFile(PortalSmokeSupport.directory().resolve("overview-pending-"+token.replace(':','-')+".png"));
                        }
                    }
                    require(overviewTicks<240,"visible static ship mesh incomplete: token="+token+" "+mesh);
                    return;
                }
                writeRenderDiagnostics(mc,body,token,"overview");
                String overviewFile="overview-"+token.replace(':','-')+".png";
                try (NativeImage overview=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    overview.writeToFile(PortalSmokeSupport.directory().resolve(overviewFile));
                }
                mesh.put("screenshot",overviewFile); overviewMeshes.put(token,mesh);
                restorePlayerCamera(mc); overviewToken=token; stable=0; return;
            }
            // Observe the real panel from the seat's local eye region without modifying
            // the player or relying on Sable's rider camera projection after an overview.
            // Ownership and the actual riding graph were checked independently above.
            if (panelCamera==null) {
                panelCamera=net.minecraft.world.entity.EntityType.ARMOR_STAND.create(mc.level);
                require(panelCamera!=null,"cannot create client panel camera");
            }
            var view=SableShipObservationCamera.panel();
            aimCamera(mc,panelCamera,shipPoint(body,center,view.eye()),shipPoint(body,center,view.target()));
            if (++stable < 15) return;
            try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                int red=0, green=0, blue=0, yellow=0;
                for (int y=pixels.getHeight()/4;y<pixels.getHeight()*3/4;y++) {
                    for (int x=pixels.getWidth()/4;x<pixels.getWidth()*3/4;x++) {
                        int abgr=pixels.getPixelRGBA(x,y), r=abgr&255,g=(abgr>>>8)&255,b=(abgr>>>16)&255;
                        if (r>g*1.5 && r>b*1.5 && r>40) red++;
                        if (g>r*1.35 && g>b*1.3 && g>40) green++;
                        if (b>r*1.3 && b>g*1.2 && b>40) blue++;
                        if (r>60 && g>50 && b<Math.min(r,g)*.7 && r<g*1.5 && g<r*1.35) yellow++;
                    }
                }
                boolean mutated=request.get("mutated").getAsBoolean();
                int roi = (pixels.getWidth()*3/4-pixels.getWidth()/4)*(pixels.getHeight()*3/4-pixels.getHeight()/4);
                if (!SableShipPanelPixels.accepts(mutated ? red : green, mutated ? green : red, blue, yellow, roi)) {
                    if (stable % 60 == 0) {
                        pixels.writeToFile(PortalSmokeSupport.directory().resolve("pending-"+token.replace(':','-')+".png"));
                        writeRenderDiagnostics(mc,body,token,"pending");
                    }
                    require(stable<240,"ship panel pixels stale/invisible: token="+token+" red="+red+" green="+green+" blue="+blue+" yellow="+yellow);
                    return;
                }
                String key=request.get("size").getAsString()+":"+request.get("route").getAsString();
                String file="ship-"+token.replace(':','-')+".png";
                pixels.writeToFile(PortalSmokeSupport.directory().resolve(file));
                writeRenderDiagnostics(mc,body,token,"panel");
                if (!cases.containsKey(key)) {
                    var report=new LinkedHashMap<String,Object>();
                    for (String field:List.of("size","route","ship_id")) report.put(field,request.get(field).getAsString());
                    for (String field:List.of("blocks","chunks")) report.put(field,request.get(field).getAsInt());
                    report.put("dimensions",java.util.stream.StreamSupport.stream(request.getAsJsonArray("dimensions").spliterator(),false).map(com.google.gson.JsonElement::getAsInt).toList());
                    report.put("assembled",true); cases.put(key,report);
                    report.put("overview_mesh",overviewMeshes.get(token));
                    crossings.put(key,new ArrayList<>()); screenshots.put(key,new ArrayList<>());
                }
                screenshots.get(key).add(file);
                int leg=request.get("leg").getAsInt();
                if (leg>=0) {
                    var record=new LinkedHashMap<String,Object>();
                    record.put("leg",leg); record.put("from",request.get("from").getAsString()); record.put("to",request.get("to").getAsString());
                    for (String field:List.of("unique_body","rider_graph","blocks","machinery","mutation")) record.put(field,true);
                    if (SableShipFlightTest.aeronautics()) record.put("propellers",true);
                    record.put("panel_pixels",mutated?red:green); record.put("opposing_panel_pixels",mutated?green:red);
                    record.put("panel_roi_pixels",roi); record.put("blue_panel_pixels",blue); record.put("yellow_panel_pixels",yellow); record.put("screenshot",file);
                    record.put("render_completeness",overviewMeshes.get(token));
                    crossings.get(key).add(record);
                }
                restorePlayerCamera(mc);
                PortalSmokeSupport.write("ship-client-ack.txt",token); observedToken=token;
            }
        } catch (Throwable error) {
            done=true; restoreStoppedAnimationPrediction(); restorePlayerCamera(mc); error.printStackTrace();
            PortalSmokeSupport.write("client-fail.txt","ships token="+pendingToken+": "+error);
            mc.stop();
        }
    }
    /** Test-only delayed prediction; authoritative position and riding packets continue normally. */
    private static boolean handleStoppedAnimationProbe(Minecraft mc) {
        String text=PortalSmokeSupport.read("stopped-animation-control.json");
        if (text.isBlank()) return false;
        JsonObject control=JsonParser.parseString(text).getAsJsonObject();
        String mode=control.get("mode").getAsString(), probeToken=control.get("token").getAsString();
        if (mode.equals("idle")) { restoreStoppedAnimationPrediction(); return false; }
        if (mode.equals("resume")) {
            restoreStoppedAnimationPrediction(); restorePlayerCamera(mc);
            String key=control.get("size").getAsString()+":ordinary";
            if (cases.containsKey(key) && cases.get(key).get("stopped_animation_witness") instanceof Map<?,?> receipt) {
                @SuppressWarnings("unchecked") var writable=(Map<String,Object>)receipt;
                writable.put("prediction_restored",true);
            }
            PortalSmokeSupport.write("stopped-animation-client-ack.txt",probeToken+":resumed");
            return true;
        }
        require(mode.equals("pause") || mode.equals("verify"),"unknown stopped-animation client phase");
        if (!stoppedAnimationPredictionPaused) {
            previousDisableTeleportation=qouteall.imm_ptl.core.IPGlobal.disableTeleportation;
            stoppedAnimationPredictionPaused=true;
            restorePlayerCamera(mc);
        }
        // The short teleport cooldown is insufficient: retained-rider requests precede
        // that check. This client-only top-level gate suppresses prediction, not packets.
        qouteall.imm_ptl.core.IPGlobal.disableTeleportation=true;
        if (mode.equals("pause")) {
            PortalSmokeSupport.write("stopped-animation-client-ack.txt",probeToken+":paused");
            return true;
        }
        String source=control.get("source").getAsString(), destination=control.get("destination").getAsString();
        if (!mc.level.dimension().location().toString().equals(destination)) return true;
        UUID id=UUID.fromString(control.get("ship_id").getAsString());
        var container=SubLevelContainer.getContainer(mc.level);
        var body=container==null?null:container.getSubLevel(id);
        if (!(body instanceof ClientSubLevel)) return true;
        var seat=mc.player.getVehicle();
        if (seat==null || !seat.getUUID().toString().equals(control.get("seat_id").getAsString())
            || !seat.getPassengers().contains(mc.player) || Sable.HELPER.getContaining(seat)!=body) return true;
        for (var dimension:ClientWorldLoader.getServerDimensions()) {
            if (!dimension.location().toString().equals(source)) continue;
            var old=SubLevelContainer.getContainer(ClientWorldLoader.getWorld(dimension));
            if (old!=null && old.getSubLevel(id)!=null) return true;
        }
        var priorObservation=JsonParser.parseString(PortalSmokeSupport.read("ship-observe.json")).getAsJsonObject();
        for (var entry:priorObservation.getAsJsonArray("blocks_manifest")) {
            var row=entry.getAsJsonArray();
            BlockPos position=body.getPlot().getCenterBlock().offset(row.get(0).getAsInt(),row.get(1).getAsInt(),row.get(2).getAsInt());
            if (Block.getId(mc.level.getBlockState(position))!=row.get(3).getAsInt()) return true;
        }
        for (var entry:priorObservation.getAsJsonArray("kinetics")) {
            var row=entry.getAsJsonArray();
            var entity=mc.level.getBlockEntity(body.getPlot().getCenterBlock().offset(row.get(0).getAsInt(),row.get(1).getAsInt(),row.get(2).getAsInt()));
            if (!(entity instanceof KineticBlockEntity kinetic) || !Float.isFinite(kinetic.getSpeed())
                || Math.abs(kinetic.getSpeed()-row.get(3).getAsFloat())>.01) return true;
        }
        var receipt=new LinkedHashMap<String,Object>();
        receipt.put("kind","held_body_stopped_source_animation"); receipt.put("counted_as_flight_crossing",false);
        receipt.put("ship_id",id.toString()); receipt.put("seat_id",seat.getUUID().toString());
        receipt.put("from",source); receipt.put("to",destination); receipt.put("dimension",mc.level.dimension().location().toString());
        receipt.put("unique_body",true); receipt.put("rider_graph",true); receipt.put("blocks",true);
        receipt.put("machinery",true); receipt.put("mutation",true);
        cases.get(control.get("size").getAsString()+":ordinary").put("stopped_animation_witness",receipt);
        PortalSmokeSupport.write("stopped-animation-client-ack.txt",probeToken+":arrived");
        return true;
    }
    private static void restoreStoppedAnimationPrediction() {
        if (!stoppedAnimationPredictionPaused) return;
        qouteall.imm_ptl.core.IPGlobal.disableTeleportation=previousDisableTeleportation;
        stoppedAnimationPredictionPaused=false;
    }
    private static org.joml.Vector3d shipPoint(ClientSubLevel body,BlockPos center,SableShipObservationCamera.Point point) {
        var absolute=point.inPlot(center.getX(),center.getY(),center.getZ());
        // Use the same interpolated pose as Sable's renderer, not the newer physics pose.
        return body.renderPose().transformPosition(new org.joml.Vector3d(absolute.x(),absolute.y(),absolute.z()));
    }
    private static String overviewCameraPortal(Minecraft mc,org.joml.Vector3d eye) {
        var riderEye=qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.getPlayerEyePos(
            mc.getTimer().getGameTimeDeltaPartialTick(true));
        return qouteall.imm_ptl.core.commands.PortalCommand.raytracePortals(mc.level,riderEye,
            new net.minecraft.world.phys.Vec3(eye.x,eye.y,eye.z),true)
            .filter(hit->hit.getFirst().canTeleportEntity(overviewCamera))
            .map(hit->hit.getFirst().getUUID().toString()).orElse(null);
    }
    /** Expectations come from received block states, never from the meshes being tested. */
    private static Map<String,Object> visibleStaticMesh(Minecraft mc,ClientSubLevel body,JsonObject request,
        SableShipObservationCamera.View requestedView,String cameraPortal) {
        var occupied=new ArrayList<SableShipObservationCamera.Point>();
        BlockPos center=body.getPlot().getCenterBlock();
        for (var entry:request.getAsJsonArray("blocks_manifest")) {
            var row=entry.getAsJsonArray();
            BlockPos pos=center.offset(row.get(0).getAsInt(),row.get(1).getAsInt(),row.get(2).getAsInt());
            var state=mc.level.getBlockState(pos);
            if (state.getRenderShape()!=net.minecraft.world.level.block.RenderShape.MODEL) continue;
            boolean exposed=false;
            for (var direction:net.minecraft.core.Direction.values()) {
                if (Block.shouldRenderFace(state,mc.level,pos,direction,pos.relative(direction))) { exposed=true; break; }
            }
            if (exposed) occupied.add(new SableShipObservationCamera.Point(pos.getX(),pos.getY(),pos.getZ()));
        }
        var camera=mc.gameRenderer.getMainCamera();
        var worldEye=camera.getPosition();
        var eye=body.renderPose().transformPositionInverse(new org.joml.Vector3d(worldEye.x,worldEye.y,worldEye.z));
        var forward=body.renderPose().orientation().transformInverse(new org.joml.Vector3d(camera.getLookVector()));
        var up=body.renderPose().orientation().transformInverse(new org.joml.Vector3d(camera.getUpVector()));
        var view=new SableShipObservationCamera.View(cameraPoint(eye),cameraPoint(new org.joml.Vector3d(eye).add(forward)));
        var requestedPlotView=new SableShipObservationCamera.View(
            requestedView.eye().inPlot(center.getX(),center.getY(),center.getZ()),
            requestedView.target().inPlot(center.getX(),center.getY(),center.getZ()));
        boolean cameraMatches=camera.getEntity()==overviewCamera
            && SableShipObservationCamera.matchesObservedView(requestedPlotView,view);
        // IP's transient teleport rotation acts on the render matrix, not Camera look/up.
        boolean cameraAnimation=qouteall.imm_ptl.core.render.TransformationManager.isAnimationRunning();
        double fov=((qouteall.imm_ptl.core.compat.mixin.sable.InvokerGameRenderer_SableProjection)mc.gameRenderer)
            .ip_sableEffectiveFov(camera,mc.getTimer().getGameTimeDeltaPartialTick(true),true);
        double aspect=(double)mc.getMainRenderTarget().width/mc.getMainRenderTarget().height;
        double viewDistance=mc.options.getEffectiveRenderDistance()*16.0;
        var visible=SableShipVisibleSections.expectedSections(occupied,view,cameraPoint(up),fov,aspect,viewDistance);
        var allOccupied=new java.util.HashSet<SableShipVisibleSections.Section>();
        for (var block:occupied) allOccupied.add(new SableShipVisibleSections.Section(
            Math.floorDiv((int)block.x(),16),Math.floorDiv((int)block.y(),16),Math.floorDiv((int)block.z(),16)));
        boolean framed=!allOccupied.isEmpty() && visible.equals(allOccupied);
        // Keep the full manifest-derived expectation even on a framing failure. Persist
        // evidence and wait for the actual rendered frame; never shrink the acceptance set.
        var ordered=allOccupied.stream().sorted(java.util.Comparator.comparingInt(SableShipVisibleSections.Section::x)
            .thenComparingInt(SableShipVisibleSections.Section::y).thenComparingInt(SableShipVisibleSections.Section::z)).toList();
        int compiledCount=0,nonemptyCount=0;
        var missing=new ArrayList<List<Integer>>();
        var results=new ArrayList<Map<String,Object>>();
        for (var key:ordered) {
            var position=List.of(key.x(),key.y(),key.z());
            var result=new LinkedHashMap<String,Object>(); result.put("position",position);
            result.put("compiled",false); result.put("nonempty",false); results.add(result);
            var section=body.getRenderData() instanceof dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData vanilla
                ? vanilla.getRenderSection(net.minecraft.core.SectionPos.of(key.x(),key.y(),key.z())) : null;
            if (section==null || section.getCompiled()==net.minecraft.client.renderer.chunk.SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                missing.add(position); continue;
            }
            compiledCount++; result.put("compiled",true);
            var compiled=section.getCompiled();
            if (!compiled.isEmpty(net.minecraft.client.renderer.RenderType.solid())
                || !compiled.isEmpty(net.minecraft.client.renderer.RenderType.cutout())
                || !compiled.isEmpty(net.minecraft.client.renderer.RenderType.cutoutMipped())
                || !compiled.isEmpty(net.minecraft.client.renderer.RenderType.translucent())) {
                nonemptyCount++; result.put("nonempty",true);
            } else missing.add(position);
        }
        var receipt=new LinkedHashMap<String,Object>();
        receipt.put("ship_id",body.getUniqueId().toString()); receipt.put("token",request.get("token").getAsString());
        receipt.put("dimension",mc.level.dimension().location().toString());
        receipt.put("view","overview"); receipt.put("expected_sections",allOccupied.size());
        receipt.put("expected_section_positions",ordered.stream().map(key->List.of(key.x(),key.y(),key.z())).toList());
        receipt.put("section_results",results);
        receipt.put("camera_plot_eye",List.of(eye.x,eye.y,eye.z));
        receipt.put("camera_plot_forward",List.of(forward.x,forward.y,forward.z));
        receipt.put("camera_plot_up",List.of(up.x,up.y,up.z));
        receipt.put("camera_world_eye",List.of(worldEye.x,worldEye.y,worldEye.z));
        receipt.put("requested_camera_plot_eye",List.of(requestedPlotView.eye().x(),requestedPlotView.eye().y(),requestedPlotView.eye().z()));
        receipt.put("requested_camera_plot_target",List.of(requestedPlotView.target().x(),requestedPlotView.target().y(),requestedPlotView.target().z()));
        receipt.put("camera_matches_requested",cameraMatches);
        receipt.put("camera_segment_clear",cameraPortal==null);
        receipt.put("camera_segment_portal",cameraPortal);
        receipt.put("camera_teleport_animation_running",cameraAnimation);
        receipt.put("visible_section_positions",visible.stream().map(key->List.of(key.x(),key.y(),key.z())).toList());
        receipt.put("vertical_fov_degrees",fov); receipt.put("aspect_ratio",aspect);
        receipt.put("view_distance_blocks",viewDistance); receipt.put("framed_all_exposed_static_sections",framed);
        receipt.put("compiled_sections",compiledCount); receipt.put("nonempty_sections",nonemptyCount);
        receipt.put("complete",framed && cameraMatches && cameraPortal==null && !cameraAnimation
            && nonemptyCount==allOccupied.size());
        receipt.put("missing_sections",missing);
        return receipt;
    }
    private static SableShipObservationCamera.Point cameraPoint(org.joml.Vector3d point) {
        return new SableShipObservationCamera.Point(point.x,point.y,point.z);
    }
    /** Read-only evidence: distinguish a missing compiled mesh from camera or lighting failures. */
    private static void writeRenderDiagnostics(Minecraft mc,ClientSubLevel body,String token,String stage) {
        var report=new LinkedHashMap<String,Object>();
        report.put("token",token); report.put("stage",stage); report.put("dimension",mc.level.dimension().location().toString());
        report.put("ship_id",body.getUniqueId().toString()); report.put("finalized",body.isFinalized());
        report.put("effective_render_distance",mc.options.getEffectiveRenderDistance());
        report.put("clouds",mc.options.getCloudsType().getSerializedName());
        float cloudHeight=mc.level.effects().getCloudHeight();
        report.put("cloud_height",Float.isFinite(cloudHeight)?cloudHeight:null);
        var camera=mc.gameRenderer.getMainCamera();
        var position=camera.getPosition();
        report.put("camera",List.of(position.x,position.y,position.z));
        report.put("camera_yaw",camera.getYRot()); report.put("camera_pitch",camera.getXRot());
        report.put("camera_entity",camera.getEntity()==null?"null":camera.getEntity().getType().toString());
        report.put("sky_light_scale",body.getLatestSkyLightScale());
        var lights=new ArrayList<List<Integer>>();
        BlockPos plotCenter=body.getPlot().getCenterBlock();
        for (int x=-1;x<=1;x++) for (int y=2;y<=4;y++) {
            BlockPos front=plotCenter.offset(x,y,1);
            lights.add(List.of(x,y,1,mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,front),
                mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY,front)));
        }
        report.put("panel_front_light",lights);
        var data=body.getRenderData();
        report.put("render_data",data==null?"null":data.getClass().getName());
        if (data instanceof dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData vanilla) {
            var sections=new ArrayList<Map<String,Object>>();
            for (var section:vanilla.allRenderSections()) {
                var item=new LinkedHashMap<String,Object>();
                BlockPos origin=section.getOrigin();
                var center=body.renderPose().transformPosition(new org.joml.Vector3d(origin.getX()+8,origin.getY()+8,origin.getZ()+8));
                item.put("origin",List.of(origin.getX(),origin.getY(),origin.getZ()));
                item.put("world_center",List.of(center.x,center.y,center.z));
                item.put("camera_distance_squared",center.distanceSquared(position.x,position.y,position.z));
                // This is the exact public helper used by Sable's RenderSectionMixin
                // override of getDistToPlayerSqr, rather than a player-distance guess.
                item.put("renderer_distance_squared",Sable.HELPER.distanceSquaredWithSubLevels(mc.level,position,
                    origin.getX()+8,origin.getY()+8,origin.getZ()+8));
                item.put("dirty",section.isDirty()); item.put("dirty_from_player",section.isDirtyFromPlayer());
                item.put("has_all_neighbors",section.hasAllNeighbors());
                // hasAllNeighbors skips its lookups within 24 blocks. Record actual
                // hidden-plot neighbors too; container.getChunk is lookup-only.
                var container=SubLevelContainer.getContainer(body.getLevel());
                var neighbors=new LinkedHashMap<String,Boolean>();
                var plotHolders=new LinkedHashMap<String,Boolean>();
                for (var direction:List.of(net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.SOUTH,
                    net.minecraft.core.Direction.WEST,net.minecraft.core.Direction.EAST)) {
                    var chunk=new net.minecraft.world.level.ChunkPos((origin.getX()>>4)+direction.getStepX(),
                        (origin.getZ()>>4)+direction.getStepZ());
                    neighbors.put(direction.getName(),body.getLevel().getChunk(chunk.x,chunk.z,
                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false)!=null);
                    plotHolders.put(direction.getName(),container!=null && container.getChunk(chunk)!=null);
                }
                item.put("horizontal_neighbors_present",neighbors);
                item.put("plot_holder_neighbors_present",plotHolders);
                var compiled=section.getCompiled();
                item.put("uncompiled",compiled==net.minecraft.client.renderer.chunk.SectionRenderDispatcher.CompiledSection.UNCOMPILED);
                item.put("solid_empty",compiled.isEmpty(net.minecraft.client.renderer.RenderType.solid()));
                item.put("cutout_empty",compiled.isEmpty(net.minecraft.client.renderer.RenderType.cutout()));
                item.put("cutout_mipped_empty",compiled.isEmpty(net.minecraft.client.renderer.RenderType.cutoutMipped()));
                item.put("translucent_empty",compiled.isEmpty(net.minecraft.client.renderer.RenderType.translucent()));
                sections.add(item);
            }
            report.put("sections",sections);
        }
        PortalSmokeSupport.write("render-"+stage+"-"+token.replace(':','-')+".json",new Gson().toJson(report));
    }
    private static void aimCamera(Minecraft mc,net.minecraft.world.entity.Entity camera,
        org.joml.Vector3d eye,org.joml.Vector3d target) {
        var look=new org.joml.Vector3d(target).sub(eye);
        require(eye.isFinite() && look.isFinite() && look.lengthSquared()>1.0e-6,"invalid ship observation camera");
        float yaw=(float)Math.toDegrees(Math.atan2(-look.x,look.z));
        camera.moveTo(eye.x,eye.y-camera.getEyeHeight(),eye.z,
            yaw,
            (float)-Math.toDegrees(Math.atan2(look.y,Math.hypot(look.x,look.z))));
        // LivingEntity view interpolation uses its head yaw, independently of Entity.yRot.
        // This camera is an ArmorStand; moveTo alone does not synchronize those fields.
        if (camera instanceof net.minecraft.world.entity.LivingEntity living) {
            living.yHeadRot=yaw; living.yHeadRotO=yaw;
            living.yBodyRot=yaw; living.yBodyRotO=yaw;
        }
        camera.setOldPosAndRot();
        mc.options.hideGui=true;
        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        mc.setCameraEntity(camera);
    }
    private static void restorePlayerCamera(Minecraft mc) {
        if (mc.player!=null) mc.setCameraEntity(mc.player);
        overviewCamera=null;
        panelCamera=null;
    }
    private static void waiting(String reason) {
        stable=0;
        if (ticks % 60 != 0) return;
        String detail="token="+pendingToken+" ticks="+ticks+" "+reason;
        PortalSmokeSupport.write("client-waiting.txt",detail);
        System.out.println("[Sable ship observation] "+detail);
        Minecraft mc=Minecraft.getInstance();
        try (NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(PortalSmokeSupport.directory().resolve("client-waiting.png"));
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot save waiting-state screenshot",error); }
    }
    private static void require(boolean value,String message) { if(!value) throw new IllegalStateException(message); }
}
