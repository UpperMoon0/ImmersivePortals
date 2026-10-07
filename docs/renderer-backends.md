# Renderer compatibility contract (Minecraft 1.21.1 / NeoForge)

Renderer and shader implementations are selected independently. Install only one terrain renderer.

| Terrain renderer | Shader implementation | Supported artifact |
| --- | --- | --- |
| Vanilla | None | Minecraft 1.21.1 |
| Sodium | None or official Iris | Sodium 0.8.12+mc1.21.1; Iris 1.8.14-beta.1+1.21.1-neoforge |
| Embeddium | None or NeOculus | Embeddium 1.0.15+mc1.21.1; NeOculus 1.8.7 (Modrinth version `ZMibIRkN`) |

Embeddium and NeOculus are pinned ABI contracts, not assertions that every shaderpack renders correctly.
Graphical acceptance requires the runtime matrix and captured framebuffer evidence.
Unsupported combinations and unreviewed Embeddium/NeOculus versions produce an actionable compatibility error.
NeOculus's dummy `iris` mod ID never selects official Iris's `SodiumShader` mixin.

## Audited upstream references

- [Embeddium fc5c69b0](https://github.com/FiniteReality/embeddium/tree/fc5c69b0b9e39f544bf57792bec3037ae2384b85)
- [NeOculus 44241916](https://github.com/Happy-FZM/ForgeOculus/tree/442419165dc3a2e727892501b4f6e7cb08b5a0fa)
- [NeOculus installable 1.8.7](https://modrinth.com/mod/neoculus/version/ZMibIRkN), SHA-1 `561550dd5a4ca05bb31b90e50bc7194c263065ff`

## Embeddium-specific integration

The adapter uses `org.embeddedt.embeddium.impl` and the public Embeddium sprite API. It never loads
`net.caffeinemc.mods.sodium` classes. The existing `SodiumInterface.Invoker` name remains the common invocation
boundary, with an independently instantiated Embeddium implementation.

- Chunk arrival/removal updates the destination world's `ChunkTracker` block-data status.
- Each recursive view swaps render lists, rebuild lists, render distance, visibility frame, camera positions,
  current viewport, and portal frustum culler. Dimension-owned mesh buffers/build jobs are shared.
- Each region has an independent `ChunkRenderList` per portal depth, preserving outer translucent geometry.
- Portal-specific visibility-search origins apply throughout graph traversal, including its outward-direction
  constraint, and are reset after traversal. Portal frustum tests preserve Embeddium's actual padded AABBs.
- Forced chunk rebuilds use Embeddium's own `FlawlessFrames`; previously overwritten visibility frames cannot
  incorrectly cull outer-view entities after a recursive render.
- Embeddium's block shader is transformed and its `ChunkShaderInterface` uploads the view-space clip equation.
  Optional uniform lookup uses the actual linked GL program because this version has no `bindUniformOptional`.

## NeOculus and the shared Iris API

NeOculus uses `compat.embeddium.impl.oculus.EmbeddiumShader`, with an Embeddium `ShaderBindingContext` and
`EmbeddiumPrograms.Pass`. A separate mixin obtains the clip uniform from its constructor's GL program handle
and uploads it after `setupState`. Shadow rendering and disabled clipping upload the neutral plane.
The superclass's null terrain-pass sentinel prevents double initialization of ordinary Embeddium uniforms.

The shared pipeline adapter was checked against the pinned NeOculus source and installable jar:

- `Iris.getCurrentPack`, `getCurrentPackName`, `getPipelineManager`
- `PipelineManager.getPipeline` and `destroyPipeline`
- `MixinLevelRenderer.pipeline : WorldRenderingPipeline` (per-LevelRenderer save/restore)
- `IrisRenderingPipeline.isRenderingWorld`, `isBeforeTranslucent`, `finalizeLevelRendering`, and
  `beginTranslucents` calling `CompositeRenderer.renderAll`
- `ClearPass.execute(Vector4f)`, `FinalPassRenderer.renderFinalPass`, `ShadowRenderer.ACTIVE`,
  `ShadowRenderTargets`, and `SystemTimeUniforms.COUNTER.beginFrame`
- `TransformPatcher.transformInternal(String, Map, Parameters)`

Shared shader creation, shader application and particle hooks are individually allowlisted for NeOculus;
new Iris compatibility classes are not implicitly enabled there. `NeOculusTargetContractTest` reads the
NeOculus jar separately from the official Iris test classpath, avoiding accidental ABI masking.

## Verification boundaries

`RendererCompatibilityTest` covers all 16 mod-ID combinations and unsupported versions.
`EmbeddiumTargetContractTest` and `NeOculusTargetContractTest` inspect actual dependency classfiles for target
fields, descriptors and call sites. These tests do not prove that Mixin transforms apply or that pixels are correct.
Client validation must additionally cover active shaderpack terrain/entities/translucency, nested portals,
mirrors, dimension crossing, and resource reload, preserving logs and images for every backend.
