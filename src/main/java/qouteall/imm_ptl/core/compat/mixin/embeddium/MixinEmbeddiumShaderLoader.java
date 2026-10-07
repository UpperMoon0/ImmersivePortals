package qouteall.imm_ptl.core.compat.mixin.embeddium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.Program;
import org.embeddedt.embeddium.impl.gl.shader.ShaderLoader;
import org.embeddedt.embeddium.impl.gl.shader.ShaderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

@Mixin(value = ShaderLoader.class)
public abstract class MixinEmbeddiumShaderLoader {
    
    @WrapOperation(
        method = "loadShader",
        at = @At(
            value = "INVOKE",
            target = "Lorg/embeddedt/embeddium/impl/gl/shader/ShaderLoader;getShaderSource(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;",
            remap = true
        ),
        remap = false
    )
    private static String wrapGetShaderSource(
        ResourceLocation name,
        Operation<String> operation,
        @Local(argsOnly = true) ShaderType shaderType
    ) {
        String shaderSource = operation.call(name);
        shaderSource = ShaderCodeTransformation.transform(
            shaderType == ShaderType.VERTEX ? Program.Type.VERTEX : Program.Type.FRAGMENT,
            name.toString(), shaderSource
        );
        return shaderSource;
    }
}
