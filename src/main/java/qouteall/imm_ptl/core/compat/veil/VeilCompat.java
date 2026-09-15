package qouteall.imm_ptl.core.compat.veil;

import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import foundry.veil.forge.event.ForgeVeilAddShaderProcessorsEvent;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.GlslSyntaxException;
import io.github.ocelot.glslprocessor.api.node.function.GlslFunctionNode;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import net.neoforged.bus.api.IEventBus;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.util.Set;

/**
 * Keeps iPortal's front-clipping transform intact when Veil recompiles vanilla
 * shaders through its own shader compiler instead of Minecraft's Program path.
 */
public final class VeilCompat {
    private static final Set<String> TERRAIN_SHADERS = Set.of(
        "rendertype_solid",
        "rendertype_cutout",
        "rendertype_cutout_mipped",
        "rendertype_translucent"
    );

    private VeilCompat() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(
            ForgeVeilAddShaderProcessorsEvent.class,
            VeilCompat::onAddShaderProcessors
        );
    }

    private static void onAddShaderProcessors(ForgeVeilAddShaderProcessorsEvent event) {
        // Only source files need the clipping declaration/assignment. Running this
        // on imports could duplicate the uniform or inject against the wrong inputs.
        event.addPreprocessorFirst(new IPortalClippingPreProcessor(), false);
    }

    static final class IPortalClippingPreProcessor implements ShaderPreProcessor {
        @Override
        public void modify(Context ctx, GlslTree tree) throws GlslSyntaxException {
            if (!ctx.isVertex() || !ctx.isSourceFile()) {
                return;
            }
            if (!(ctx instanceof MinecraftContext minecraftContext)) {
                return;
            }

            String shaderName = minecraftContext.shaderInstance();
            if (!ShaderCodeTransformation.shouldAddUniform(shaderName)) {
                return;
            }

            GlslFunctionNode main = tree.mainFunction().orElse(null);
            if (main == null || main.getBody() == null) {
                return;
            }

            if (tree.field("iportal_ClippingEquation").isEmpty()) {
                tree.getBody().add(0, GlslParser.parseExpression(
                    "uniform vec4 iportal_ClippingEquation"
                ));
            }

            String position = TERRAIN_SHADERS.contains(shaderName)
                ? "Position.xyz + ChunkOffset"
                : "Position.xyz";

            // Match the vanilla Program transform exactly, but operate on Veil's
            // parsed GLSL tree so Veil's asynchronous recompilation cannot bypass it.
            main.getBody().add(0, GlslParser.parseExpression(
                "gl_ClipDistance[0] = dot(" + position +
                    ", iportal_ClippingEquation.xyz) + iportal_ClippingEquation.w"
            ));
        }
    }
}