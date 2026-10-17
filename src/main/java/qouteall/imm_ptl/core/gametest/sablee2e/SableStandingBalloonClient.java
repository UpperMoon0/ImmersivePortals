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
    private static int ticks, currentCase=-1, stable, readinessWaitTicks, coldTicks, coldSamples;
    private static boolean connecting, done;
    private static double maximumError;
    private static String coldNonce;
    private static long unsuccessfulAtStart;
    private static net.minecraft.client.multiplayer.ClientLevel sourceWorld;
    private static java.util.Map<String,Object> sourceReadiness;
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
            if(observeColdStart(mc,request)) return;
            if(request.isEmpty())return;
            if(mc.player.getVehicle()!=null)throw new IllegalStateException("balloon test became mounted");
            var json=JsonParser.parseString(request).getAsJsonObject();
            int testCase=json.get("case").getAsInt();
            UUID id=UUID.fromString(json.get("body").getAsString());
            if(testCase!=currentCase){
                currentCase=testCase;stable=readinessWaitTicks=0;maximumError=0;
                unsuccessfulAtStart=SableServerFirstClientHandoff.getUnsuccessfulStandingHandoffs();
                sourceWorld=null;sourceReadiness=null;
            }
            var container=SubLevelContainer.getContainer(mc.level);
            var body=container==null?null:container.getSubLevel(id);
            boolean supported=body!=null && !body.isRemoved() && Sable.HELPER.getTrackingSubLevel(mc.player)==body;
            var expectedFeet=new net.minecraft.world.phys.Vec3(json.get("feet_x").getAsDouble(),
                json.get("feet_y").getAsDouble(),json.get("feet_z").getAsDouble());
            if(ticks%100==0 && mc.level.dimension().equals(Level.NETHER) && body!=null)
                PortalSmokeSupport.write("balloon-preflight-client.json",body.logicalPose().transformPositionInverse(mc.player.position())+" expected "+expectedFeet+" support="+supported);
            if(mc.level.dimension().equals(Level.NETHER) && supported
                && body.logicalPose().transformPositionInverse(mc.player.position()).distanceTo(expectedFeet)<.2) {
                var candidateReadiness=SableStandingBalloonChunks.snapshot(mc.level,body,mc.player.position());
                if(ticks%40==0) PortalSmokeSupport.write("balloon-preflight-readiness-client-"+testCase+".json",
                    new com.google.gson.Gson().toJson(java.util.Map.of("case",testCase,"body",id.toString(),
                        "readiness",candidateReadiness)));
                if(!SableStandingBalloonChunks.ready(candidateReadiness)) return;
                sourceWorld=mc.level; sourceReadiness=candidateReadiness;
                PortalSmokeSupport.write("balloon-client-ready-"+testCase+".txt","native standing support "+id);
            }
            if(mc.level.dimension().equals(Level.OVERWORLD)
                && !(testCase==0 && PortalSmokeSupport.exists("balloon-reset.txt"))
                && SableServerFirstClientHandoff.getActiveHandoffSnapshot()==null) {
                if(!supported)throw new IllegalStateException("Client lost standing support after handoff");
                var completion=SableServerFirstClientHandoff.getStandingCompletionSnapshot();
                long failedAttempts=SableServerFirstClientHandoff.getUnsuccessfulStandingHandoffs()-unsuccessfulAtStart;
                if(completion==null || !SableStandingBalloonAcceptance.completed(id,completion.supportId(),completion.handoffId(),
                    completion.sourceDimension().location().toString(),completion.destinationDimension().location().toString(),failedAttempts))
                    throw new IllegalStateException("Missing clean correlated standing completion for "+id+" completion="+completion+" failed="+failedAttempts);
                if(sourceWorld==null || sourceReadiness==null)throw new IllegalStateException("Missing observed source readiness");
                var sourceContainer=SubLevelContainer.getContainer(sourceWorld);
                boolean sourceRetired=sourceContainer==null || sourceContainer.getSubLevel(id)==null;
                var destinationReadiness=SableStandingBalloonChunks.snapshot(mc.level,body,mc.player.position());
                double error=body.logicalPose().transformPositionInverse(mc.player.position()).distanceTo(expectedFeet);
                maximumError=Math.max(maximumError,error);
                PortalSmokeSupport.write("balloon-standing-client-"+testCase+".json",new com.google.gson.Gson().toJson(
                    java.util.Map.of("samples",stable+1,"error",error,"maximum",maximumError,"supported",supported)));
                if(error>=1)throw new IllegalStateException("Client standing displacement="+error);
                // Keep enforcing support/error while bounded packet catch-up precedes settled samples.
                if(!sourceRetired || !SableStandingBalloonChunks.ready(destinationReadiness)) {
                    if(stable>0)throw new IllegalStateException("Client settled ownership/chunk readiness regressed");
                    if(++readinessWaitTicks>200)throw new IllegalStateException("Client ownership/chunks did not settle");
                    return;
                }
                if(++stable>=40) {
                    PortalSmokeSupport.write("balloon-client-case-"+testCase+".json",new com.google.gson.Gson().toJson(
                        java.util.Map.ofEntries(java.util.Map.entry("case",testCase),java.util.Map.entry("body",id.toString()),
                            java.util.Map.entry("cold_start",json.get("cold_start").getAsBoolean()),
                            java.util.Map.entry("cold_nonce",json.get("cold_nonce").getAsString()),
                            java.util.Map.entry("handoff",completion.handoffId().toString()),java.util.Map.entry("handoff_completed",true),
                            java.util.Map.entry("failed_handoffs",failedAttempts),java.util.Map.entry("source_body_absent",true),
                            java.util.Map.entry("destination_body_present",true),java.util.Map.entry("supported",supported),
                            java.util.Map.entry("source_readiness",sourceReadiness),java.util.Map.entry("destination_readiness",destinationReadiness),
                            java.util.Map.entry("samples",stable),java.util.Map.entry("maximum_error",maximumError))));
                    PortalSmokeSupport.write("balloon-client-arrived-"+testCase+".txt","stable unmounted destination support "+id);
                }
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
    private static boolean observeColdStart(Minecraft mc,String bodyRequest) throws java.io.IOException {
        if(!"true".equals(System.getenv("IP_SABLE_STANDING_COLD_START"))) return false;
        String request=PortalSmokeSupport.read("balloon-cold-request.json");
        if(request.isEmpty()) return false;
        var cold=JsonParser.parseString(request).getAsJsonObject();
        int testCase=cold.get("case").getAsInt();
        if(!bodyRequest.isEmpty() && JsonParser.parseString(bodyRequest).getAsJsonObject().get("case").getAsInt()>=testCase)
            return false;
        String nonce=cold.get("nonce").getAsString();
        if(!nonce.equals(coldNonce)) { coldNonce=nonce; coldTicks=coldSamples=0; }
        if(++coldTicks>600) throw new IllegalStateException("Client cold destination observation exceeded 600 ticks");
        int remoteX=cold.get("remote_x").getAsInt(),remoteZ=cold.get("remote_z").getAsInt();
        // Enumerate existing client worlds only; never create a destination world for the probe.
        net.minecraft.client.multiplayer.ClientLevel existing=mc.level.dimension().equals(Level.OVERWORLD)?mc.level:null;
        if(qouteall.imm_ptl.core.ClientWorldLoader.getIsInitialized()) {
            for(var world:qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds())
                if(world.dimension().equals(Level.OVERWORLD)) existing=world;
        }
        var chunks=SableStandingBalloonColdChunks.snapshot(existing,remoteX,remoteZ);
        coldSamples=SableStandingBalloonAcceptance.allAbsent(chunks)?coldSamples+1:0;
        String receipt="balloon-cold-client-"+testCase+".json";
        if(coldSamples<20) {
            java.nio.file.Files.deleteIfExists(PortalSmokeSupport.directory().resolve(receipt));
            return true;
        }
        PortalSmokeSupport.write(receipt,new com.google.gson.Gson().toJson(java.util.Map.of(
            "case",testCase,"nonce",nonce,"dimension",Level.OVERWORLD.location().toString(),
            "remote_x",remoteX,"remote_z",remoteZ,"samples",coldSamples,"world_chunks",chunks,
            "world_existed",existing!=null)));
        return true;
    }

}
