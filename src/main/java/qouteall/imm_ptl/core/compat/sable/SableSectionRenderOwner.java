package qouteall.imm_ptl.core.compat.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Immutable render-thread snapshots supply safe ownership to asynchronous mesh tasks. */
public final class SableSectionRenderOwner {
    // Only accessed on the client thread. Weak ownership does not retain removed ships/worlds.
    private static final Set<SableSectionRenderOwner> OWNERS=Collections.newSetFromMap(new WeakHashMap<>());
    private record Origin(int x,int z) {}
    private record Snapshot(ClientLevel level,int minX,int minZ,int maxX,int maxZ,
        Map<SectionRenderDispatcher.RenderSection,Origin> sections) {}
    private final ClientSubLevel body;
    private final SableRenderOwnerEpoch<Snapshot> epoch=new SableRenderOwnerEpoch<>();

    public SableSectionRenderOwner(ClientSubLevel body) {
        this.body=body;
        OWNERS.add(this);
    }

    public void beginResize() { epoch.invalidate(); }

    /** Called on the render thread, after Sable has finished replacing its section array. */
    public void refresh(List<SectionRenderDispatcher.RenderSection> current) {
        if (epoch.isClosed()) return;
        epoch.invalidate();
        for (var section:current) ((SableOwnedRenderSection)section).ip_setSableRenderOwner(this);
        var level=body.getLevel();
        var container=SubLevelContainer.getContainer(level);
        boolean liveLevel=ClientWorldLoader.getIsInitialized()
            ? ClientWorldLoader.getClientWorlds().contains(level) : Minecraft.getInstance().level==level;
        if (!liveLevel || body.isRemoved() || !body.isFinalized() || container==null
            || container.getSubLevel(body.getUniqueId())!=body) return;
        var bounds=body.getPlot().getBoundingBox();
        if (bounds==null) return;
        Map<SectionRenderDispatcher.RenderSection,Origin> sections=new IdentityHashMap<>();
        for (var section:current) {
            var origin=section.getOrigin();
            sections.put(section,new Origin(origin.getX()>>4,origin.getZ()>>4));
        }
        epoch.publish(new Snapshot(level,bounds.minX()>>4,bounds.minZ()>>4,bounds.maxX()>>4,bounds.maxZ()>>4,
            Collections.unmodifiableMap(sections)));
    }

    public void close() { epoch.close(); }

    public static void closeAll() {
        for (var owner:new ArrayList<>(OWNERS)) owner.close();
        OWNERS.clear();
    }

    public static void closeDimension(ResourceKey<Level> dimension) {
        for (var owner:new ArrayList<>(OWNERS)) {
            if (owner.body.getLevel().dimension().equals(dimension)) {
                owner.close(); OWNERS.remove(owner);
            }
        }
    }

    public boolean canUseExteriorAir(SectionRenderDispatcher.RenderSection section) {
        return epoch.test(state->{
            Origin origin=state.sections().get(section);
            if (origin==null) return false;
            // IP's client cache synchronizes these off-thread noncreating reads.
            // All mutable body/container/bounds facts were copied on the render thread.
            return SableSectionNeighbors.canUseExteriorAir(true,true,
                state.level().getChunk(origin.x(),origin.z(),ChunkStatus.FULL,false)!=null,origin.x(),origin.z(),
                state.minX(),state.minZ(),state.maxX(),state.maxZ(),
                (x,z)->state.level().getChunk(x,z,ChunkStatus.FULL,false)!=null);
        });
    }
}
