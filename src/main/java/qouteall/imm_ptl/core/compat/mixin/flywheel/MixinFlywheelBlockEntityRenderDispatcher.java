package qouteall.imm_ptl.core.compat.mixin.flywheel;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply Flywheel's normal suppression at draw time, not chunk compilation time. */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class MixinFlywheelBlockEntityRenderDispatcher {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity> void ip_skipVisualizedMainViewEntry(
        E blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci
    ) {
        if (VisualizationManager.supportsVisualization(blockEntity.getLevel())
            && VisualizationHelper.skipVanillaRender(blockEntity)) {
            ci.cancel();
        }
    }
}
