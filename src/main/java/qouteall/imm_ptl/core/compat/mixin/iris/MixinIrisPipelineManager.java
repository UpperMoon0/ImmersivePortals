package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IEIrisPipelineManager;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisDeferredRendererReloads;

@Mixin(value = PipelineManager.class, remap = false)
public class MixinIrisPipelineManager implements IEIrisPipelineManager {
    @Shadow private WorldRenderingPipeline pipeline;

    @WrapOperation(method = "preparePipeline", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;allChanged()V"))
    private void ip_deferMaterialReload(LevelRenderer renderer, Operation<Void> original) {
        if (!IrisDeferredRendererReloads.defer(renderer, (PipelineManager) (Object) this, pipeline)) {
            original.call(renderer);
        }
    }

    @Inject(method = "destroyPipeline", at = @At("HEAD"))
    private void ip_discardStaleReloads(CallbackInfo ci) {
        IrisDeferredRendererReloads.clear();
    }

    @Override
    public void ip_setPipeline(WorldRenderingPipeline pipeline) {
        this.pipeline = pipeline;
    }
}
