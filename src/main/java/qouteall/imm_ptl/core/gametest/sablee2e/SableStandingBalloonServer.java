package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.compat.sable.SableStandingPassenger;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.*;

/** Real burner ascent: no seat, support injection, forced loading, or flight-time body writes. */
final class SableStandingBalloonServer {
    private static ServerPlayer player;
    private static ServerLevel source, destination;
    private static UUID id;
    private static Vector3d held;
    private static int ticks, phaseTicks, testCase, phase;
    private static Vec3 localFeet;
    private static double sourceY, arrivalY;
    private static final List<Map<String,Object>> receipts = new ArrayList<>();
    private static final Map<BlockPos,BlockState> manifest = new LinkedHashMap<>();
    static boolean enabled() { return "true".equals(System.getenv("IP_SABLE_STANDING_BALLOON")); }
    static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) { player=p; ticks=phaseTicks=testCase=phase=0; }
    }
    static void tick(ServerTickEvent.Post e) {
        if (player==null || phase==99) return;
        try {
            if (++ticks>4000) throw new IllegalStateException("balloon timeout case="+testCase+" phase="+phase+state());
            phaseTicks++;
            if (phase==0 && phaseTicks>=40) setup();
            else if (phase==1) warmup();
            else if (phase==2) fly();
            else if (phase==3) observe();
        } catch (Throwable error) {
            phase=99; error.printStackTrace();
            PortalSmokeSupport.write("server-fail.txt",error+state());
        }
    }
    private static void setup() {
        source=player.server.getLevel(Level.OVERWORLD); destination=player.server.getLevel(Level.NETHER);
        // Upward Overworld ceiling -> Nether floor, using real global stack connectors.
        VerticalConnectingPortal.connectMutually(Level.NETHER, Level.OVERWORLD, false);
        for (BlockPos pos : BlockPos.betweenClosed(-12,0,-12,12,64,12))
            if (!destination.getBlockState(pos).isAir()) destination.setBlock(pos,Blocks.AIR.defaultBlockState(),2);
        manifest.clear();
        for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) {
            manifest.put(new BlockPos(x,0,z),Blocks.OAK_PLANKS.defaultBlockState());
            manifest.put(new BlockPos(x,7,z),state("aeronautics:white_envelope"));
            for(int y=3;y<7;y++) if(Math.abs(x)==2 || Math.abs(z)==2)
                manifest.put(new BlockPos(x,y,z),state("aeronautics:white_envelope"));
        }
        for(int x:new int[]{-2,2}) for(int z:new int[]{-2,2}) for(int y=1;y<3;y++)
            manifest.put(new BlockPos(x,y,z),Blocks.OAK_FENCE.defaultBlockState());
        manifest.put(new BlockPos(0,1,0),state("aeronautics:adjustable_burner"));
        manifest.put(new BlockPos(1,1,0),Blocks.LEVER.defaultBlockState()
            .setValue(BlockStateProperties.ATTACH_FACE,net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
            .setValue(BlockStateProperties.POWERED,true));
        BlockPos origin=new BlockPos(0,32,0);
        List<BlockPos> positions=new ArrayList<>();
        for(var entry:manifest.entrySet()) { BlockPos pos=origin.offset(entry.getKey()); source.setBlock(pos,entry.getValue(),3); positions.add(pos); }
        // Publish placement callbacks (connected fences/powered burner) as the exact expected state.
        manifest.replaceAll((pos,old)->source.getBlockState(origin.offset(pos)));
        var body=(ServerSubLevel)SubLevelAssemblyHelper.assembleBlocks(source,origin,positions,
            new BoundingBox3i(-2,32,-2,3,40,3));
        id=body.getUniqueId();
        // Stage near the real build-limit seam before releasing real burner propulsion.
        var handle=RigidBodyHandle.of(body);
        Vector3d position=new Vector3d(body.logicalPose().position());
        Vec3 roof=body.logicalPose().transformPosition(Vec3.atBottomCenterOf(body.getPlot().getCenterBlock().offset(0,8,0)));
        position.y += source.getMaxBuildHeight()-12-roof.y;
        handle.teleport(position,body.logicalPose().orientation());
        SubLevelContainer.getContainer(source).physicsSystem().updatePose(body);
        body.updateLastPose(); body.updateBoundingBox(); held=new Vector3d(body.logicalPose().position());
        localFeet=Vec3.atBottomCenterOf(body.getPlot().getCenterBlock().offset(testCase==0?0:-1,testCase==0?8:1,0));
        Vec3 feet=body.logicalPose().transformPosition(localFeet);
        ServerTeleportationManager.of(player.server).forceTeleportPlayer(player,source.dimension(),feet);
        phase=1; phaseTicks=0;
        PortalSmokeSupport.write("balloon-request.json",new Gson().toJson(Map.of(
            "case",testCase,"body",id.toString(),"standing",testCase==0?"envelope":"deck","blocks",manifest.size())));
    }
    private static void warmup() throws Exception {
        ServerSubLevel body=body(source);
        require(body!=null,"source balloon disappeared during warmup");
        var handle=RigidBodyHandle.of(body);
        // Explicit pre-flight observation hold, never used after release.
        handle.teleport(held,body.logicalPose().orientation());
        handle.addLinearAndAngularVelocity(handle.getLinearVelocity(new Vector3d()).negate(),
            handle.getAngularVelocity(new Vector3d()).negate());
        SubLevelContainer.getContainer(source).physicsSystem().updatePose(body);
        require(player.getVehicle()==null,"standing fixture mounted a vehicle");
        if(phaseTicks<100 || !PortalSmokeSupport.exists("balloon-client-ready-"+testCase+".txt")) return;
        require(SableStandingPassenger.getSupport(player)==body,"native collision did not establish standing support");
        Object burner=burner(body,source);
        Object balloon=burner.getClass().getMethod("getBalloon").invoke(burner);
        if(balloon==null) return;
        double lift=((Number)balloon.getClass().getMethod("getTotalLift").invoke(balloon)).doubleValue();
        require(lift>0,"burner produced no natural lift");
        require(SubLevelContainer.getContainer(source).collectForceLoadTickets().get(body)==null
            || SubLevelContainer.getContainer(source).collectForceLoadTickets().get(body).isEmpty(),"fixture unexpectedly force-loaded");
        sourceY=body.logicalPose().position().y;
        PortalSmokeSupport.write("balloon-release-"+testCase+".txt","lift="+lift+" sourceY="+sourceY);
        phase=2; phaseTicks=0;
    }
    private static void fly() {
        require(player.getVehicle()==null && player.isAlive(),"standing passenger died or mounted during flight");
        ServerSubLevel arrived=body(destination);
        if(player.serverLevel()==destination) {
            require(arrived!=null && body(source)==null,"player crossed without exclusive destination ship ownership");
            require(SableStandingPassenger.getSupport(player)==arrived,"standing support still references the source ship");
            require(arrived.logicalPose().position().y>sourceY-source.getMaxBuildHeight()+3,
                "balloon did not make natural pre-seam travel");
            arrivalY=arrived.logicalPose().position().y;
            phase=3;phaseTicks=0;
        } else require(body(source)!=null,"ship migrated without standing passenger");
    }
    private static void observe() throws Exception {
        ServerSubLevel body=body(destination);
        require(player.serverLevel()==destination && body!=null && body(source)==null,"ship/player ownership split after crossing");
        require(player.isAlive() && player.getVehicle()==null,"standing passenger died or became mounted");
        Vec3 relative=body.logicalPose().transformPositionInverse(player.position());
        require(relative.distanceTo(localFeet)<1.0,"standing passenger fell off: local displacement="+relative.distanceTo(localFeet));
        for(var entry:manifest.entrySet()) require(body.getPlot().getEmbeddedLevelAccessor()
            .getBlockState(body.getPlot().getCenterBlock().offset(entry.getKey())).equals(entry.getValue()),"balloon block changed: "+entry.getKey());
        if(phaseTicks<80 || !PortalSmokeSupport.exists("balloon-client-arrived-"+testCase+".txt")) return;
        Object burner=burner(body,destination);
        require((boolean)burner.getClass().getMethod("canOutputGas").invoke(burner),"destination burner stopped");
        receipts.add(Map.of("standing",testCase==0?"envelope":"deck","blocks",manifest.size(),"support",true,
            "force_loaded",false,"source_y",sourceY,"arrival_y",arrivalY,"observed_y",body.logicalPose().position().y,
            "standing_error",relative.distanceTo(localFeet),"native_flight_body_writes",0));
        if(++testCase==2) {
            PortalSmokeSupport.write("balloon-server.json",new Gson().toJson(receipts));
            PortalSmokeSupport.write("server-pass.txt","Both unmounted burner-balloon stack ascents passed");
            phase=99;
        } else {
            // Reuse the original live player; safely stage before removing the old ship.
            ServerTeleportationManager.of(player.server).forceTeleportPlayer(player,Level.OVERWORLD,new Vec3(0,100,0));
            SubLevelContainer.getContainer(destination).removeSubLevel(body,SubLevelRemovalReason.REMOVED);
            setup();
        }
    }
    private static Object burner(ServerSubLevel body,ServerLevel level) {
        return level.getBlockEntity(body.getPlot().getCenterBlock().offset(0,1,0));
    }
    private static ServerSubLevel body(ServerLevel level) {
        if(level==null || id==null) return null;
        return (ServerSubLevel)SubLevelContainer.getContainer(level).getSubLevel(id);
    }
    private static BlockState state(String key) {
        ResourceLocation id=ResourceLocation.parse(key);
        require(BuiltInRegistries.BLOCK.containsKey(id),"missing fixture block "+id);
        return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
    }
    private static void require(boolean condition,String reason) { if(!condition) throw new IllegalStateException(reason); }
    private static String state() {
        return " player="+(player==null?"null":player.position()+" "+player.level().dimension())+
            " source="+body(source)+" destination="+body(destination);
    }
}
