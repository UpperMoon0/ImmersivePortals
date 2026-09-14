package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable.SableClientPacketContext;
import qouteall.imm_ptl.core.compat.sable.SableServerFirstClientHandoff;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Mixin(value = ClientboundStartTrackingSubLevelPacket.class, remap = false)
public abstract class MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow public abstract long plotCoordinate();
    @Shadow public abstract UUID subLevelID();
    @Shadow public abstract Pose3dc lastPose();
    @Shadow public abstract Pose3d pose();
    @Shadow public abstract int gameTick();

    @Unique private List<ip_SnapshotCopy> ip_sourceHistory;
    @Unique private Pose3d ip_sourceReference;
    @Unique private int ip_sourceReferenceTick;

    /**
     * Capture the still-live source world's interpolation buffer before the destination full
     * sync allocates its new ClientSubLevel. This keeps rendering continuous even when the
     * client's interpolation pointer is several ticks behind the two snapshots in full-sync.
     */
    @Inject(method = "handle", at = @At("HEAD"))
    private void ip_captureSourceInterpolation(PacketContext context, CallbackInfo ci) {
        Level destinationLevel = SableClientPacketContext.resolve(context);
        ip_sourceHistory = null;
        ip_sourceReference = null;
        ip_sourceReferenceTick = Integer.MIN_VALUE;

        for (ResourceKey<Level> dimension : ClientWorldLoader.getServerDimensions()) {
            if (dimension.equals(destinationLevel.dimension())) continue;
            ClientLevel sourceWorld = ClientWorldLoader.getOptionalWorld(dimension);
            if (sourceWorld == null) continue;
            SubLevelContainer sourceContainer = SubLevelContainer.getContainer(sourceWorld);
            if (!(sourceContainer instanceof ClientSubLevelContainer)) continue;
            SubLevel candidate = sourceContainer.getSubLevel(subLevelID());
            if (!(candidate instanceof ClientSubLevel sourceSubLevel)) continue;

            List<ip_SnapshotCopy> history = new ArrayList<>();
            for (SubLevelSnapshotInterpolator.Snapshot snapshot : sourceSubLevel.getInterpolator().buffer) {
                Pose3d copied = new Pose3d(snapshot.pose());
                history.add(new ip_SnapshotCopy(snapshot.gameTick(), copied));
                if (snapshot.gameTick() <= gameTick() && snapshot.gameTick() > ip_sourceReferenceTick) {
                    ip_sourceReferenceTick = snapshot.gameTick();
                    ip_sourceReference = new Pose3d(snapshot.pose());
                }
            }
            if (ip_sourceReference == null) {
                ip_sourceReference = new Pose3d(sourceSubLevel.logicalPose());
                ip_sourceReferenceTick = gameTick();
            }
            ip_sourceHistory = history;
            return;
        }
    }

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
            }
            else {
                LOGGER.info(
                    "Accepting Sable client sublevel full sync dim={} plot={},{}",
                    level.dimension().location(), plotX, plotZ
                );
            }
        }
        return container;
    }

    @Inject(method = "handle", at = @At("RETURN"))
    private void ip_graftSourceInterpolation(PacketContext context, CallbackInfo ci) {
        if (ip_sourceHistory == null || ip_sourceHistory.isEmpty() || ip_sourceReference == null) return;

        SubLevelContainer rawContainer = SubLevelContainer.getContainer(SableClientPacketContext.resolve(context));
        if (!(rawContainer instanceof ClientSubLevelContainer destinationContainer)) return;
        SubLevel rawDestination = destinationContainer.getSubLevel(subLevelID());
        if (!(rawDestination instanceof ClientSubLevel destination)) return;

        Pose3dc destinationReference;
        if (ip_sourceReferenceTick == gameTick() - 1) {
            destinationReference = lastPose();
        }
        else {
            destinationReference = pose();
        }

        DQuaternion exactPortalRotation = SableServerFirstClientHandoff.getActiveInterpolationRotation(
            SableClientPacketContext.resolve(context).dimension()
        );
        Quaterniond rotationDelta = exactPortalRotation != null
            ? new Quaterniond(
                exactPortalRotation.x, exactPortalRotation.y,
                exactPortalRotation.z, exactPortalRotation.w
            )
            : new Quaterniond(destinationReference.orientation())
                .mul(new Quaterniond(ip_sourceReference.orientation()).invert());
        Vector3d rotatedSourceReference = rotationDelta.transform(
            new Vector3d(ip_sourceReference.position())
        );
        Vector3d translation = new Vector3d(destinationReference.position())
            .sub(rotatedSourceReference);

        SubLevelSnapshotInterpolator interpolator = destination.getInterpolator();
        for (ip_SnapshotCopy snapshot : ip_sourceHistory) {
            if (snapshot.gameTick() >= gameTick() - 1) continue;
            boolean duplicate = interpolator.buffer.stream()
                .anyMatch(existing -> existing.gameTick() == snapshot.gameTick());
            if (duplicate) continue;
            interpolator.buffer.add(new SubLevelSnapshotInterpolator.Snapshot(
                snapshot.gameTick(),
                ip_transformHistoricalPose(snapshot.pose(), rotationDelta, translation)
            ));
        }
        interpolator.buffer.sort(Comparator.comparingInt(SubLevelSnapshotInterpolator.Snapshot::gameTick));

        // Re-sample logical/last poses using the now-complete history. The start packet already
        // built render data and bounds, so only bounds need refreshing after the pose graft.
        destination.setInitialPosesFrom(destinationContainer.getInterpolation());
        destination.forceUpdateBounds();

        ip_sourceHistory = null;
        ip_sourceReference = null;
    }

    @Unique
    private static Pose3d ip_transformHistoricalPose(
        Pose3dc source, Quaterniond rotationDelta, Vector3d translation
    ) {
        Pose3d transformed = new Pose3d(source);
        Vector3d position = rotationDelta.transform(new Vector3d(source.position())).add(translation);
        transformed.position().set(position);
        transformed.orientation().premul(rotationDelta);
        return transformed;
    }

    @Unique
    private record ip_SnapshotCopy(int gameTick, Pose3d pose) {}
}
