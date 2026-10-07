package qouteall.imm_ptl.core.compat.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.ExperimentalIrisPortalRenderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IEIrisNewWorldRenderingPipeline;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisDeferredRendererReloads;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline implements IEIrisNewWorldRenderingPipeline {
    @Shadow private boolean isRenderingWorld;

    @WrapOperation(method = "beginLevelRendering", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;allChanged()V"))
    private void ip_deferBlockMappingReload(LevelRenderer renderer, Operation<Void> original) {
        if (!IrisDeferredRendererReloads.defer(renderer, Iris.getPipelineManager(), (IrisRenderingPipeline) (Object) this)) {
            original.call(renderer);
        }
    }

//    @Shadow private ShadowRenderTargets shadowRenderTargets;

    @Inject(
        method = "finalizeLevelRendering", at = @At("HEAD"), cancellable = true
    )
    private void onFinalizeLevelRendering(CallbackInfo ci) {
        if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer) {
            if (PortalRendering.isRendering()) {
                ci.cancel();
            }
        }
    }

    @Inject(
        method = "beginTranslucents",
        at = @At(
            value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterDeferredCompositeRendering(CallbackInfo ci) {
        if (IPCGlobal.renderer instanceof ExperimentalIrisPortalRenderer r) {
            r.onAfterIrisDeferredCompositeRendering();
        }
    }

    @Override
    public void ip_setIsRenderingWorld(boolean cond) {
        isRenderingWorld = cond;
    }

//    @Override
//    public ShadowRenderTargets ip_getShadowRenderTargets() {
//        return shadowRenderTargets;
//    }
}
