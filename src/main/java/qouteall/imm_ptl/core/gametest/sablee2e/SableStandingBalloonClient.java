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
            if(mc.player.getVehicle()!=null)throw new IllegalStateException("balloon test became mounted");
            String request=PortalSmokeSupport.read("balloon-request.json");
            if(request.isEmpty())return;
            var json=JsonParser.parseString(request).getAsJsonObject();
            int testCase=json.get("case").getAsInt();
            UUID id=UUID.fromString(json.get("body").getAsString());
            if(testCase!=currentCase){currentCase=testCase;stable=0;}
            var container=SubLevelContainer.getContainer(mc.level);
            var body=container==null?null:container.getSubLevel(id);
            boolean supported=body!=null && !body.isRemoved() && Sable.HELPER.getTrackingSubLevel(mc.player)==body;
            if(mc.level.dimension().equals(Level.NETHER) && supported) {
                PortalSmokeSupport.write("balloon-client-ready-"+testCase+".txt","native standing support "+id);
            }
            if(mc.level.dimension().equals(Level.OVERWORLD) && supported
                && SableServerFirstClientHandoff.getActiveHandoffSnapshot()==null) {
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
