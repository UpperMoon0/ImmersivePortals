package qouteall.imm_ptl.core.compat.mixin.neoculus;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.NeOculusDimensionState;

@Mixin(value = Iris.class, remap = false)
public class MixinNeOculusIris {
    @Shadow public static NamespacedId lastDimension;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void ip_initializeDimensionCache(CallbackInfo ci) {
        // NeOculus 1.8.7 initializes this only from the first vanilla TitleScreen.init.
        // Quick-connect/custom-title flows can call Minecraft.disconnect before that,
        // where iris$resetPipeline dereferences getCurrentDimension() with no world.
        // Use the exact same default as onLoadingComplete, without preparing a pipeline
        // before GL initialization or altering any subsequent dimension transitions.
        lastDimension = NeOculusDimensionState.initializeIfAbsent(lastDimension, DimensionId.OVERWORLD);
    }
}
