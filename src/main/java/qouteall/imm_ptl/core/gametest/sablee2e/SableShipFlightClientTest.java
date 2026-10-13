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
    private static String observedToken = "";
    private static int ticks, stable;
    private static String pendingToken = "";
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
                require(cases.size() == 4, "server completed before four client cases");
                for (String key : cases.keySet()) {
                    require(crossings.get(key).size() == 4,"missing client crossings for " + key);
                    cases.get(key).put("crossings",crossings.get(key));
                    cases.get(key).put("screenshots",screenshots.get(key));
                    cases.get(key).put("snapshots",screenshots.get(key).size());
                }
                PortalSmokeSupport.write("client-ships.json",new Gson().toJson(Map.of("schema_version",1,"cases",cases.values())));
                PortalSmokeSupport.write("client-pass.txt","Real client observed all sixteen assembled-ship crossings, block/kinetic mutations and contrasting framebuffer states");
                done = true; mc.stop(); return;
            }
            String text = PortalSmokeSupport.read("ship-observe.json");
            if (text.isBlank()) return;
            JsonObject request = JsonParser.parseString(text).getAsJsonObject();
            String token = request.get("token").getAsString();
            if (token.equals(observedToken)) return;
            if (!token.equals(pendingToken)) { pendingToken = token; stable = 0; }
            if (!mc.level.dimension().location().toString().equals(request.get("dimension").getAsString())) { stable=0; return; }
            UUID id = UUID.fromString(request.get("ship_id").getAsString());
            var container = SubLevelContainer.getContainer(mc.level);
            var rawBody = container == null ? null : container.getSubLevel(id);
            if (!(rawBody instanceof ClientSubLevel body)) { stable=0; return; }
            var seat = mc.player.getVehicle();
            if (seat == null || !seat.getUUID().toString().equals(request.get("seat_id").getAsString())
                || !seat.getPassengers().contains(mc.player) || Sable.HELPER.getContaining(seat) != body) { stable=0; return; }
            var otherDimension = mc.level.dimension().equals(Level.OVERWORLD) ? Level.NETHER : Level.OVERWORLD;
            if (ClientWorldLoader.getServerDimensions().contains(otherDimension)) {
                var other = SubLevelContainer.getContainer(ClientWorldLoader.getWorld(otherDimension));
                if (other != null && other.getSubLevel(id) != null) { stable=0; return; }
            }
            BlockPos center = body.getPlot().getCenterBlock();
            for (var item : request.getAsJsonArray("blocks_manifest")) {
                var row = item.getAsJsonArray();
                var position = center.offset(row.get(0).getAsInt(),row.get(1).getAsInt(),row.get(2).getAsInt());
                if (Block.getId(mc.level.getBlockState(position)) != row.get(3).getAsInt()) { stable=0; return; }
            }
            for (var item : request.getAsJsonArray("kinetics")) {
                var row = item.getAsJsonArray();
                var be = mc.level.getBlockEntity(center.offset(row.get(0).getAsInt(),row.get(1).getAsInt(),row.get(2).getAsInt()));
                if (!(be instanceof KineticBlockEntity kinetic) || Math.abs(kinetic.getSpeed()-row.get(3).getAsFloat())>.01) { stable=0; return; }
            }
            // The view is local to the retained Create seat. Let normal render frames settle after camera input.
            mc.options.hideGui = true;
            mc.player.setYRot(0); mc.player.setXRot(-12);
            if (++stable < 15) return;
            try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                int red=0, green=0;
                for (int y=pixels.getHeight()/4;y<pixels.getHeight()*3/4;y++) {
                    for (int x=pixels.getWidth()/4;x<pixels.getWidth()*3/4;x++) {
                        int abgr=pixels.getPixelRGBA(x,y), r=abgr&255,g=(abgr>>>8)&255,b=(abgr>>>16)&255;
                        if (r>g*1.5 && r>b*1.5 && r>40) red++;
                        if (g>r*1.35 && g>b*1.3 && g>40) green++;
                    }
                }
                boolean mutated=request.get("mutated").getAsBoolean();
                if ((mutated ? red : green) < 1000) {
                    if (stable % 60 == 0) pixels.writeToFile(PortalSmokeSupport.directory().resolve("pending-"+token.replace(':','-')+".png"));
                    require(stable<240,"ship panel pixels stale/invisible: token="+token+" red="+red+" green="+green);
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
                    record.put("panel_pixels",mutated?red:green); record.put("screenshot",file);
                    crossings.get(key).add(record);
                }
                PortalSmokeSupport.write("ship-client-ack.txt",token); observedToken=token;
            }
        } catch (Throwable error) {
            done=true; error.printStackTrace();
            PortalSmokeSupport.write("client-fail.txt","ships token="+pendingToken+": "+error);
            mc.stop();
        }
    }
    private static void require(boolean value,String message) { if(!value) throw new IllegalStateException(message); }
}
