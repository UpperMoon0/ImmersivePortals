package qouteall.imm_ptl.core.compat;

import java.util.Set;

/** Selection must not load optional renderer classes, including during Mixin discovery. */
public record RendererCompatibility(Renderer renderer, Shaders shaders) {
    private static final Set<String> SHARED_IRIS_MIXINS = Set.of(
        "MixinIrisClearPass", "MixinIrisFinalPassRenderer", "MixinIrisIris",
        "MixinIrisRenderingPipeline", "MixinIrisPipelineManager", "MixinIrisShadowRenderTargets", "MixinIrisTransformPatcher",
        "MixinIrisWorldShader", "MixinIrisShaderCreator", "MixinIrisVertexBuffer",
        "MixinIrisShadowRenderer", "MixinIrisFullScreenQuadRenderer", "MixinIrisGLDebug"
    );
    public enum Renderer { VANILLA, SODIUM, EMBEDDIUM }
    public enum Shaders { NONE, IRIS, NEOCULUS }

    public static RendererCompatibility select(boolean sodium, boolean embeddium, boolean iris, boolean oculus) {
        if (sodium && embeddium) {
            throw unsupported("Sodium and Embeddium cannot be installed together. Remove one renderer.");
        }
        if (oculus && !embeddium) {
            throw unsupported("NeOculus requires Embeddium 1.0.15+mc1.21.1. Remove Sodium and install that Embeddium build.");
        }
        // NeOculus exposes a dummy 'iris' ID. It is not the official Iris/Sodium adapter.
        if (!oculus && iris && !sodium) {
            throw unsupported("Official Iris requires Sodium. For Embeddium use NeOculus 1.8.7 instead of Iris.");
        }
        return new RendererCompatibility(
            embeddium ? Renderer.EMBEDDIUM : sodium ? Renderer.SODIUM : Renderer.VANILLA,
            oculus ? Shaders.NEOCULUS : iris ? Shaders.IRIS : Shaders.NONE
        );
    }

    public void checkVersions(String embeddiumVersion, String oculusVersion) {
        if (renderer == Renderer.EMBEDDIUM && !"1.0.15+mc1.21.1".equals(embeddiumVersion)
            && !"1.0.15".equals(embeddiumVersion)) {
            throw unsupported("Embeddium " + embeddiumVersion + " is unsupported. Install Embeddium 1.0.15+mc1.21.1.");
        }
        if (shaders == Shaders.NEOCULUS && !"1.8.7".equals(oculusVersion)) {
            throw unsupported("NeOculus " + oculusVersion + " is unsupported. Install NeOculus 1.8.7 for Minecraft 1.21.1.");
        }
    }

    public boolean appliesTo(String mixinClassName) {
        if (mixinClassName.contains(".embeddium.")) return renderer == Renderer.EMBEDDIUM;
        if (mixinClassName.contains(".neoculus.")) return shaders == Shaders.NEOCULUS;
        if (mixinClassName.contains(".sodium.")) return renderer == Renderer.SODIUM;
        if (mixinClassName.contains(".iris.")) {
            if (mixinClassName.contains("IrisSodium")) return shaders == Shaders.IRIS;
            // These targets are shared only after auditing the pinned NeOculus ABI.
            return shaders == Shaders.IRIS || (shaders == Shaders.NEOCULUS
                && SHARED_IRIS_MIXINS.contains(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1)));
        }
        return false;
    }

    private static IllegalStateException unsupported(String message) {
        return new IllegalStateException("Immersive Portals renderer compatibility: " + message);
    }
}
