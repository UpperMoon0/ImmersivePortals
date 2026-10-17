package qouteall.imm_ptl.core.compat.sable;

import com.mojang.serialization.Codec;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Optional Aeronautics state lives in a world map, outside Sable's serialized blocks. */
final class SableAeronauticsMigration {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT = "dev.eriksonn.aeronautics.content.blocks.hot_air.";
    private record BalloonState(BlockPos heater, BlockPos controller, Object saved) {}
    private final ServerLevel source;
    private final List<BalloonState> states;
    private final Class<?> mapType, balloonType, savedType, providerType;

    private SableAeronauticsMigration(ServerLevel source, List<BalloonState> states,
        Class<?> mapType, Class<?> balloonType, Class<?> savedType, Class<?> providerType) {
        this.source=source; this.states=states; this.mapType=mapType;
        this.balloonType=balloonType; this.savedType=savedType; this.providerType=providerType;
    }

    static SableAeronauticsMigration capture(ServerSubLevel body) {
        Class<?> provider;
        try { provider=Class.forName(ROOT+"BlockEntityLiftingGasProvider"); }
        catch (ClassNotFoundException absent) { return null; }
        try {
            Class<?> map=Class.forName(ROOT+"balloon.map.BalloonMap");
            Class<?> balloon=Class.forName(ROOT+"balloon.ServerBalloon");
            Class<?> saved=Class.forName(ROOT+"balloon.map.SavedBalloon");
            var result=new SableAeronauticsMigration(body.getLevel(),new ArrayList<>(),map,balloon,saved,provider);
            // Validate cleanup and rebuild APIs before staging any destination state.
            map.getMethod("getBalloons"); map.getMethod("getUnloadedBalloons"); map.getMethod("markDirty");
            balloon.getMethod("onRemoved"); balloon.getMethod("loadFrom",saved);
            saved.getMethod("controllerPos");
            provider.getMethod("doRaycast"); provider.getMethod("tryJoinBalloon"); provider.getMethod("tryCreateBalloon");
            result.map(body.getLevel());
            List<Object> seen=new ArrayList<>();
            for(var holder:body.getPlot().getLoadedChunks())
                for(var entity:holder.getChunk().getBlockEntities().values()) {
                    if(!provider.isInstance(entity)) continue;
                    Object live=provider.getMethod("getBalloon").invoke(entity);
                    if(!balloon.isInstance(live) || seen.contains(live)) continue;
                    BlockPos controller=(BlockPos)balloon.getMethod("getControllerPos").invoke(live);
                    if(Sable.HELPER.getContaining(body.getLevel(),controller)!=body)
                        throw new IllegalStateException("Aeronautics balloon spans different Sable owners");
                    Object state=map.getMethod("saveBalloon",balloon).invoke(null,live);
                    // saveBalloon shares mutable gas amounts. Codec roundtrip isolates rollback.
                    @SuppressWarnings("unchecked") Codec<Object> codec=(Codec<Object>)saved.getField("CODEC").get(null);
                    var ops=RegistryOps.create(NbtOps.INSTANCE,body.getLevel().registryAccess());
                    Object copy=codec.parse(ops,codec.encodeStart(ops,state).getOrThrow()).getOrThrow();
                    result.states.add(new BalloonState(entity.getBlockPos(),controller,copy));
                    seen.add(live);
                }
            return result;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot capture Aeronautics balloon state",error); }
    }

    void restore(ServerLevel destination) {
        try {
            for(var state:states) {
                Object heater=destination.getBlockEntity(state.heater());
                if(!providerType.isInstance(heater)) throw new IllegalStateException("Destination balloon heater missing");
                providerType.getMethod("doRaycast").invoke(heater);
                providerType.getMethod("tryJoinBalloon").invoke(heater);
                providerType.getMethod("tryCreateBalloon").invoke(heater);
                Object live=providerType.getMethod("getBalloon").invoke(heater);
                if(!balloonType.isInstance(live)) throw new IllegalStateException("Destination balloon could not rebuild");
                balloonType.getMethod("loadFrom",savedType).invoke(live,state.saved());
            }
            if(!states.isEmpty()) mapType.getMethod("markDirty").invoke(map(destination));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot restore Aeronautics balloon state",error); }
    }

    void retireSource() { discard(source); }
    void discard(ServerLevel level) {
        if(states.isEmpty()) return;
        try {
            Object map=map(level);
            var iterator=((Iterable<?>)mapType.getMethod("getBalloons").invoke(map)).iterator();
            while(iterator.hasNext()) {
                Object live=iterator.next();
                BlockPos controller=(BlockPos)live.getClass().getMethod("getControllerPos").invoke(live);
                if(owns(controller)) {
                    live.getClass().getMethod("onRemoved").invoke(live);
                    iterator.remove();
                }
            }
            var unloaded=((Collection<?>)mapType.getMethod("getUnloadedBalloons").invoke(map)).iterator();
            while(unloaded.hasNext()) {
                Object saved=unloaded.next();
                if(owns((BlockPos)savedType.getMethod("controllerPos").invoke(saved))) unloaded.remove();
            }
            mapType.getMethod("markDirty").invoke(map);
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Cleanup must not interrupt an already committed body/player handoff.
            LOGGER.error("Failed retiring migrated Aeronautics state in {}", level.dimension().location(), error);
        }
    }
    private boolean owns(BlockPos controller) {
        return states.stream().anyMatch(state->state.controller().equals(controller));
    }
    private Object map(ServerLevel level) throws ReflectiveOperationException {
        Object attached=mapType.getField("MAP").get(null);
        return attached.getClass().getMethod("get",LevelAccessor.class).invoke(attached,level);
    }
}
