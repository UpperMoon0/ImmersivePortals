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
                    require(crossings.get(key).size() == 4,"missing client crossings for " + key);
                    cases.get(key).put("crossings",crossings.get(key));
                    cases.get(key).put("screenshots",screenshots.get(key));
                    cases.get(key).put("snapshots",screenshots.get(key).size());
                }
                var report=new LinkedHashMap<String,Object>(); report.put("schema_version",1); report.put("cases",cases.values());
                if (SableShipFlightTest.aeronautics()) report.put("addons",SableShipFlightTest.addonVersions());
                PortalSmokeSupport.write("client-ships.json",new Gson().toJson(report));
                PortalSmokeSupport.write("client-pass.txt","Real client observed " + (SableShipFlightTest.expectedCases()*4) + " assembled-ship crossings, live block/kinetic mutations and contrasting framebuffer states");
                restoreStoppedAnimationPrediction(); restorePlayerCamera(mc); done = true; mc.stop(); return;
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
            if (request.get("leg").getAsInt()==-1 && !overviewToken.equals(token)) {
                if (overviewCamera==null) {
                    writeRenderDiagnostics(mc,body,token,"before-overview");
                    overviewCamera=net.minecraft.world.entity.EntityType.ARMOR_STAND.create(mc.level);
                    require(overviewCamera!=null,"cannot create client overview camera");
                    overviewTicks=0;
                }
                var dimensions=request.getAsJsonArray("dimensions");
                double width=dimensions.get(0).getAsDouble(), height=dimensions.get(1).getAsDouble(), length=dimensions.get(2).getAsDouble();
                // Pose3d transforms absolute hidden-plot positions, not relative fixture offsets.
                var view=SableShipObservationCamera.overview(width,height,length);
                aimCamera(mc,overviewCamera,shipPoint(body,center,view.eye()),shipPoint(body,center,view.target()));
                if (++overviewTicks<25) return;
                writeRenderDiagnostics(mc,body,token,"overview");
                try (NativeImage overview=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    overview.writeToFile(PortalSmokeSupport.directory().resolve("overview-"+token.replace(':','-')+".png"));
                }
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
    /** Read-only evidence: distinguish a missing compiled mesh from camera or lighting failures. */
    private static void writeRenderDiagnostics(Minecraft mc,ClientSubLevel body,String token,String stage) {
        var report=new LinkedHashMap<String,Object>();
        report.put("token",token); report.put("stage",stage); report.put("dimension",mc.level.dimension().location().toString());
        report.put("ship_id",body.getUniqueId().toString()); report.put("finalized",body.isFinalized());
        report.put("effective_render_distance",mc.options.getEffectiveRenderDistance());
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
                for (var direction:List.of(net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.SOUTH,
                    net.minecraft.core.Direction.WEST,net.minecraft.core.Direction.EAST)) {
                    var chunk=new net.minecraft.world.level.ChunkPos((origin.getX()>>4)+direction.getStepX(),
                        (origin.getZ()>>4)+direction.getStepZ());
                    neighbors.put(direction.getName(),container!=null && container.getChunk(chunk)!=null);
                }
                item.put("horizontal_neighbors_present",neighbors);
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
