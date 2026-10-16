package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.JsonParser;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import qouteall.imm_ptl.core.compat.sable.SableServerFirstClientHandoff;
import java.util.UUID;

final class SableStandingBalloonClient {
    private static int ticks, currentCase=-1, stable;
    private static boolean connecting, done;
    private static double maximumError;
    static void tick(ClientTickEvent.Post e) {
        if(done)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            if(++ticks>4000)throw new IllegalStateException("standing balloon client timeout");
            if(mc.player==null || mc.level==null) {
                if(!connecting && mc.screen!=null) {
                    connecting=true;
                    String address="127.0.0.1:"+System.getenv("IP_SABLE_E2E_PORT");
                    ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString(address),
                        new ServerData("Standing balloon",address,ServerData.Type.OTHER),true,null);
                }
                return;
            }
            if(PortalSmokeSupport.exists("balloon-reset.txt") && mc.level.dimension().equals(Level.NETHER)
                && mc.player.position().distanceTo(new net.minecraft.world.phys.Vec3(32.5,81,.5))<.2
                && mc.player.onGround() && Sable.HELPER.getTrackingSubLevel(mc.player)==null) {
                PortalSmokeSupport.write("balloon-client-reset.txt","Native ordinary collision settled");
            }
            String request=PortalSmokeSupport.read("balloon-request.json");
            if(request.isEmpty())return;
            if(mc.player.getVehicle()!=null)throw new IllegalStateException("balloon test became mounted");
            var json=JsonParser.parseString(request).getAsJsonObject();
            int testCase=json.get("case").getAsInt();
            UUID id=UUID.fromString(json.get("body").getAsString());
            if(testCase!=currentCase){currentCase=testCase;stable=0;maximumError=0;}
            var container=SubLevelContainer.getContainer(mc.level);
            var body=container==null?null:container.getSubLevel(id);
            boolean supported=body!=null && !body.isRemoved() && Sable.HELPER.getTrackingSubLevel(mc.player)==body;
            var expectedFeet=new net.minecraft.world.phys.Vec3(json.get("feet_x").getAsDouble(),
                json.get("feet_y").getAsDouble(),json.get("feet_z").getAsDouble());
            if(ticks%100==0 && mc.level.dimension().equals(Level.NETHER) && body!=null)
                PortalSmokeSupport.write("balloon-preflight-client.json",body.logicalPose().transformPositionInverse(mc.player.position())+" expected "+expectedFeet+" support="+supported);
            if(mc.level.dimension().equals(Level.NETHER) && supported
                && body.logicalPose().transformPositionInverse(mc.player.position()).distanceTo(expectedFeet)<.2) {
                PortalSmokeSupport.write("balloon-client-ready-"+testCase+".txt","native standing support "+id);
            }
            if(mc.level.dimension().equals(Level.OVERWORLD)
                && !(testCase==0 && PortalSmokeSupport.exists("balloon-reset.txt"))
                && SableServerFirstClientHandoff.getActiveHandoffSnapshot()==null) {
                if(!supported)throw new IllegalStateException("Client lost standing support after handoff");
                double error=body.logicalPose().transformPositionInverse(mc.player.position()).distanceTo(expectedFeet);
                maximumError=Math.max(maximumError,error);
                PortalSmokeSupport.write("balloon-standing-client-"+testCase+".json",new com.google.gson.Gson().toJson(
                    java.util.Map.of("samples",stable+1,"error",error,"maximum",maximumError,"supported",supported)));
                if(error>=1)throw new IllegalStateException("Client standing displacement="+error);
                if(++stable>=40)PortalSmokeSupport.write("balloon-client-arrived-"+testCase+".txt","stable unmounted destination support "+id);
            } else stable=0;
            if(PortalSmokeSupport.exists("server-pass.txt")) {
                if(!PortalSmokeSupport.exists("balloon-client-arrived-1.txt"))return;
                PortalSmokeSupport.write("client-pass.txt","Both standing balloon client handoffs stable");
                done=true;mc.stop();
            }
        }catch(Throwable error) {
            error.printStackTrace(); PortalSmokeSupport.write("client-fail.txt",error.toString()); done=true;mc.stop();
        }
    }
}
