package qouteall.imm_ptl.core.compat.mixin.sable;

import com.mojang.logging.LogUtils;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ClientboundStartTrackingSubLevelPacket.class, remap = false)
public abstract class MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow public abstract long plotCoordinate();

    @WrapOperation(
        method = "handle",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;getContainer(Lnet/minecraft/world/level/Level;)Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;"
        )
    )
    private SubLevelContainer ip_replaceExistingPlotBeforeFullSync(
        Level level, Operation<SubLevelContainer> original
    ) {
        SubLevelContainer container = original.call(level);
        int plotX = ChunkPos.getX(this.plotCoordinate());
        int plotZ = ChunkPos.getZ(this.plotCoordinate());
        if (container instanceof ClientSubLevelContainer) {
            SubLevel existing = container.getSubLevel(plotX, plotZ);
            if (existing != null) {
                LOGGER.info(
                    "Replacing stale Sable client sublevel before full sync dim={} plot={},{}",
                    level.dimension().location(), plotX, plotZ
                );
                container.removeSubLevel(existing, SubLevelRemovalReason.REMOVED);
            } else {
                LOGGER.info(
                    "Accepting Sable client sublevel full sync dim={} plot={},{}",
                    level.dimension().location(), plotX, plotZ
                );
            }
        }
        return container;
    }
}
