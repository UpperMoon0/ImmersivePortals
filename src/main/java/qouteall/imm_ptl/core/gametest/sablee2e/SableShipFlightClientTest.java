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
    private static final Map<String,Map<String,Object>> cases = new LinkedHashMap<>();
    private static final Map<String,List<Map<String,Object>>> crossings = new LinkedHashMap<>();
    private static final Map<String,List<String>> screenshots = new LinkedHashMap<>();

    public static void onClientTick(ClientTickEvent.Post event) {
        if (!SableShipFlightTest.enabled() || done) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (++ticks > 20000) throw new IllegalStateException("graphical ship test timeout, token=" + pendingToken);
            if (mc.player == null || mc.level == null) {
                if (!connecting && mc.screen != null) {
                    connecting = true;
                    String address = "127.0.0.1:" + System.getenv().getOrDefault("IP_SABLE_E2E_PORT","25565");
                    ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(address),
                        new ServerData("Complex Sable ship test",address,ServerData.Type.OTHER),true,null);
                }
                return;
            }
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
                done = true; mc.stop(); return;
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
                    var dimensions=request.getAsJsonArray("dimensions");
                    double width=dimensions.get(0).getAsDouble(), height=dimensions.get(1).getAsDouble(), length=dimensions.get(2).getAsDouble();
                    var target=body.logicalPose().transformPosition(new org.joml.Vector3d(0,height/2,0));
                    var position=new org.joml.Vector3d(target).add(Math.max(12,width*.8),Math.max(8,height*1.5),Math.max(16,length*.8));
                    var look=new org.joml.Vector3d(target).sub(position);
                    overviewCamera=net.minecraft.world.entity.EntityType.ARMOR_STAND.create(mc.level);
                    require(overviewCamera!=null,"cannot create client overview camera");
                    overviewCamera.moveTo(position.x,position.y,position.z,
                        (float)Math.toDegrees(Math.atan2(-look.x,look.z)),
                        (float)-Math.toDegrees(Math.atan2(look.y,Math.hypot(look.x,look.z))));
                    mc.options.hideGui=true; mc.setCameraEntity(overviewCamera); overviewTicks=0;
                }
                if (++overviewTicks<25) return;
                try (NativeImage overview=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    overview.writeToFile(PortalSmokeSupport.directory().resolve("overview-"+token.replace(':','-')+".png"));
                }
                mc.setCameraEntity(mc.player); overviewCamera=null; overviewToken=token; stable=0; return;
            }
            // The view is local to the retained Create seat. Let normal render frames settle after camera input.
            mc.options.hideGui = true;
            mc.player.setYRot(0); mc.player.setXRot(-12);
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
                    if (stable % 60 == 0) pixels.writeToFile(PortalSmokeSupport.directory().resolve("pending-"+token.replace(':','-')+".png"));
                    require(stable<240,"ship panel pixels stale/invisible: token="+token+" red="+red+" green="+green+" blue="+blue+" yellow="+yellow);
                    return;
                }
                String key=request.get("size").getAsString()+":"+request.get("route").getAsString();
                String file="ship-"+token.replace(':','-')+".png";
                pixels.writeToFile(PortalSmokeSupport.directory().resolve(file));
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
                PortalSmokeSupport.write("ship-client-ack.txt",token); observedToken=token;
            }
        } catch (Throwable error) {
            done=true; if(mc.player!=null) mc.setCameraEntity(mc.player); error.printStackTrace();
            PortalSmokeSupport.write("client-fail.txt","ships token="+pendingToken+": "+error);
            mc.stop();
        }
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
