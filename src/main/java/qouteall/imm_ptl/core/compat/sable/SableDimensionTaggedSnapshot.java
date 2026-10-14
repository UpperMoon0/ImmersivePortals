package qouteall.imm_ptl.core.compat.sable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Optional negotiated provenance on Sable's two dual-transport snapshot types. */
public interface SableDimensionTaggedSnapshot {
    void ip_setSnapshotDimension(ResourceKey<Level> dimension);
    @Nullable ResourceKey<Level> ip_getSnapshotDimension();
}
