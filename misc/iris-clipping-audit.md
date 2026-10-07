# Iris world-geometry clipping audit

## Source and binary contracts

Audited official Iris `1.21.1` revision `eb7afb9` and the published
`iris-neoforge-1.8.14-beta.1+mc1.21.1.jar` (SHA-1
`1bd129cf7bce65d6b1c0543045f0620c98a0f885`). The published jar's transform,
shader constructor, fallback creation and apply signatures were checked with
`javap`, rather than assuming that a similarly named fork has the same API.
Sodium source is pinned to `mc1.21.1-0.8.12`, revision
`53306ac4db8f9fae1655c81539ffcd79e4afc4fb`. NeOculus `4424191` has the shared
Iris transform/world-shader API and its separate `EMBEDDIUM` terrain patch.

- `SodiumPrograms.transformShaders` passes the resolved `ProgramSource` name.
  Terrain solid/cutout and water can fall back through terrain, textured-lit,
  textured and basic. All these names are explicitly selected for the terrain
  adapters. Selection also checks the patch family.
- `ShaderCreator.create` passes `ShaderKey.getName()` to `patchVanilla`.
  Entities, block entities, particles, translucent variants and their fallback
  sources therefore use *draw-path* names such as `entities_solid` and
  `block_entity`, rather than `gbuffers_entities` or `gbuffers_block`.
- `ShaderCreator.createFallback` synthesizes a shader without TransformPatcher
  when no pack source is available. It has its own guarded transformation hook.
- Iris's transform cache deliberately excludes the program name. Clipping is
  applied after cache lookup to a fresh map; cached source is never modified.
  A shadow/sky program sharing source with a world program remains unchanged.

## Coordinates and shader stages

The plane uploaded by both terrain and world-shader adapters is
`FrontClipping.getActiveClipPlaneEquationAfterModelView()`. The vertex-producing
stage reconstructs homogeneous view position from its final `gl_Position`
using the inverse of the drawing path's projection matrix:

- Sodium/Embeddium terrain: `iris_ProjectionMatrix`, uploaded by
  `SodiumShader.setProjectionMatrix` / `EmbeddiumShader.setProjectionMatrix`
- Iris ExtendedShader: `iris_ProjMat`, the shader-instance projection uniform
- Iris synthesized FallbackShader: `ProjMat`

This avoids assuming that entity/particle attributes contain terrain positions,
avoids applying a model-view matrix twice, and includes pack vertex displacement.
As with other projection-reconstruction methods, packs that replace the standard
projection with a fundamentally different nonlinear mapping need separate
compatibility assessment.

Only the final stage producing rasterized vertices is modified: geometry if
present, otherwise tessellation evaluation, otherwise vertex. Earlier vertex and
tessellation-control output do not need to forward a new varying. Main is wrapped
so early returns and helper functions after main are safe. Geometry clipping is
written immediately before each emission, with `EmitStreamVertex`'s constant
stream argument preserved. An explicitly redeclared output `gl_PerVertex` gains
the clip-distance member when needed.

## Uniform and state lifetime

`SodiumShader.setupState` uploads the clipping equation for each terrain draw.
`ExtendedShader` and `FallbackShader` override vanilla apply, so a separate mixin
uploads the equation after each apply. These updates cannot use Iris's per-frame
uniform cache: a frame can contain multiple nested portal planes.

Particle origin culling is not sufficient for billboards crossing the plane,
and translucent entity/block-entity buffers can flush after the old entity
clipping scope ends. A scope around `VertexBuffer._drawWithShader` enables inner
clipping only for transformed Iris shaders with an actual clipping uniform. It
uses that draw's exact model-view matrix and restores the previous enabled/disabled
state even when rendering throws. Shadow, hand and post-processing shaders do
not have the marker/uniform and cannot activate this scope. Unpatched buffered
draws suspend an inherited coarse clipping scope only while a shaderpack is
active. Existing `IEShader` clipping uniforms, including `portal_area`, preserve
their vanilla/IP scopes and camera-relative coordinate path. With shaders off,
the draw policy preserves all vanilla/IP clipping behavior. Iris full-screen quads bypass
`drawWithShader`, so `FullScreenQuadRenderer.renderQuad` has a separate scoped
suspension. The complete shadow pass also suspends clipping. All scopes restore
the previous plane, logical state and actual GL enable bit in `finally`.

The uniform is neutral `(0, 0, 0, 1)` when clipping is inactive, disabled or in a
shadow pass. The shader explicitly emits distance `1` for that neutral value.
Shadow, sky, hand, composite, deferred, final, compute and Distant Horizons paths
are not selected by this transformation. Existing clipping scopes around terrain,
entities and weather are retained; no full-screen pass is given a portal plane.

## Verification boundaries

`ShaderClippingTransformationTest` exercises the actual transformation, including
all resolved terrain fallbacks, world draw keys, exclusions, cache isolation,
final-stage selection, geometry emission scopes, explicit interface blocks,
comments, early returns, idempotence and neutral output. These are source
regressions, not claims of Minecraft graphical acceptance.

`python tools/verify_shader_clipping_gl.py` runs an off-screen Mesa EGL check.
On OpenGL 4.5 it compiled and linked generated vertex,
geometry and tessellation-evaluation paths and rasterized a triangle across the
plane: the excluded half had zero colored pixels, the retained half had 841;
with a neutral plane both halves had 841. This validates synthetic GLSL stage
behavior only. The active-pack Minecraft fixture, real packs, nested portals,
mirrors and shader/resource reload must be verified by the graphical matrix.

## Representative real-pack inputs

`iris-real-pack-inputs.json` pins official, unmodified MakeUp Ultra Fast 9.4a
and Complementary Reimagined r5.5.1 downloads and hashes for local test runs.
These established versions both list Minecraft 1.21.1 and Iris in their official
release metadata. MakeUp's bundled README states Iris 1.5.1 or newer; the
Complementary source contains Iris 1.8 compatibility branches. This is evidence
for choosing test inputs, not proof that either pack passes on NeOculus.

Both packs use the standard world projection path, including foliage/water
movement and TAA offsets. Neither input contains geometry/tessellation shaders.
Keep the downloaded ZIPs and their notices unchanged and outside this repository;
do not redistribute pack shader code as part of the regression fixture. Real-pack
smoke runs must use their own acceptance criteria rather than the fixture's
artificial program colors. Record actual activation, compile/link results,
portal output and reload behavior for each renderer separately.

## Vanilla and shaders-off damage overlays

Vanilla `rendertype_crumbling` previously had neither a clipping transformation
nor a late draw scope. Its buffer is flushed after the entity scope ends, allowing
excluded Create fallback damage geometry to cover a portal. Its vertices are
camera-relative; the added YAML rule uses `Position` and the before-model-view
plane. A narrowly selected VertexBuffer scope populates that uniform immediately
before apply, then restores plane/GL state and neutralizes the uniform in `finally`.
Iris-owned Extended/Fallback shaders are explicitly excluded even if a name collides.

`CrumblingClippingRegressionTest` checks the real YAML transformation, coordinate
choice, float upload overload and finally restoration. The optional
`verify_vanilla_crumbling_gl.py` compiles the production YAML-generated shader and
runs actual EGL draws: the unpatched excluded triangle obscures all 256 sampled
background pixels, the patched triangle obscures none, and retained/neutral-plane
controls still draw. These cases passed on Mesa OpenGL 4.5 and an actual 3.3
context. Exact-head Minecraft screenshots remain the runtime integration check.
