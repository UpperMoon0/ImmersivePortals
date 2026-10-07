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
- Base chunk loaders send a one-chunk block/light-data halo beyond the visible radius. Both optimized
  backends require a complete 3×3 neighborhood before building any visible chunk. Visibility caps still
  apply before this prerequisite ring, including cap 1, nested destinations, and oblique views at chunk
  boundaries. CE replaces vanilla's main-view packet tracking, so its main-view loader also restores the
  outer ring normally supplied by vanilla. Configured indirect caps are normalized to 1–32; zero visible
  radius means the center plus its neighbors, negative inputs are normalized, and overflow is rejected.
- Each recursive view swaps render lists, render distance, current viewport, and portal frustum culler.
  Rebuild queues, their visibility frame and camera positions, mesh buffers and build jobs remain owned
  by the dimension renderer: Embeddium consumes previous-pass discoveries before collecting visibility
  for the next pass. Build timestamps and translucency-sort origins must remain associated with that work.
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
NeOculus terrain is compiled by `compat.embeddium.impl.monocle.ShaderTransformer`, which bypasses Iris's
`TransformPatcher`. Its separate post-cache hook transforms the selected terrain/water program without
mutating the name-independent upstream cache; shared Iris hooks still handle other world shaders.

NeOculus 1.8.7 initializes its remembered dimension only during the first vanilla title-screen initialization.
A NeOculus-only static initializer supplies that same Overworld default before early quick-connect/disconnect
flows can dereference it. It does not create a shader pipeline before GL initialization, override an existing
dimension, or replace NeOculus's normal login, disconnect, dimension-change or resource-reload handlers.

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

An optional production-helper GL test is available without a display server:

```sh
python tools/verify_framebuffer_copy_gl.py \
  --minecraft-jar /path/to/built/neoforge-21.1.228.jar \
  --egl-jar /path/to/lwjgl-egl-3.3.3.jar \
  --gradle-home "$GRADLE_USER_HOME" --report framebuffer-copy-gl.txt
```

The EGL dependency is `org.lwjgl:lwjgl-egl:3.3.3` from Maven Central. The test does not download dependencies.
It compiles the current production `IPIrisHelper` and uses the real Minecraft `RenderTarget` carrier with
already-created GL attachments, bypassing its game-only constructor. It checks D24S8/D32FS8 storage,
resized buffers, full and partial copies, and GL state preservation using copy-image and forced blit paths.
Passing this focused GL test does not establish that a Minecraft mixin applies or a portal renders correctly.
With `--clipping-classpath` set to the built main classes and their runtime dependencies (a platform-separated
classpath), it also checks the actual `FrontClipping` class: all four logical/GL enable combinations,
nested suspension, and exact restoration of both clip equations. No Minecraft class is stubbed.

To test a context which genuinely advertises no copy-image capability rather than merely forcing the
fallback on a capable context, use Mesa's normal test overrides:

```sh
MESA_GL_VERSION_OVERRIDE=3.3 MESA_EXTENSION_OVERRIDE=-GL_ARB_copy_image \
  python tools/verify_framebuffer_copy_gl.py --gl-version 3.3 --require-native-blit \
  --minecraft-jar /path/to/built/neoforge-21.1.228.jar \
  --egl-jar /path/to/lwjgl-egl-3.3.3.jar --gradle-home "$GRADLE_USER_HOME" \
  --report framebuffer-copy-gl33.txt
```

The probe verifies the advertised GL 4.3/ARB capability flags are both absent and checks automatic blit
selection before setting any force flag. Copy-image support requires the advertised capability and a
valid entry point; the capability truth table also covers loaders returning an address without support.
