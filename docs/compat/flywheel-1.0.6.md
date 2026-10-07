# Create 6 / Flywheel 1.0.6 portal rendering

Audited artifacts: Create `com.simibubi.create:create-1.21.1:6.0.10-280`
(tag `mc1.21.1-6.0.10`, commit `ac0c444d9828da3453ae8cc65338e8de063286fb`),
and its bundled Flywheel `dev.engine-room.flywheel:flywheel-neoforge-1.21.1:1.0.6`.
Flywheel's Maven source artifact is the authoritative reference; the current
`1.21.1/dev` branch is newer and must not substitute for that artifact.

## Lifecycle findings

- `VisualizationManagerImpl.MANAGERS` is `LevelAttached`: a manager belongs to one
  level. `BackendManagerImpl.onReloadLevelRenderer(level)` resets that level.
  Successful resource reload resets all managers and reselects the backend.
- `LevelRendererMixin.allChanged` dispatches a reload for its own level. Creating
  an IP remote renderer must not suppress this event. The obsolete
  `com.jozufozu` global compiler, quad converter and crumbling hooks are removed.
- Flywheel also deletes its shared uniform buffers on renderer reload. They are
  lazily rebound by the next backend draw; no cancellation is required.
- Flywheel keeps one `flywheel$renderContext` field per LevelRenderer. A nested
  render of the same level overwrites it and clears it on return. IP now saves
  and restores that field with `try/finally` around the entire render call.

## Scoped fallback

Flywheel 1.0.6's instancing and indirect paths include `internal/common.vert`,
which does not write IP's clip distance. These paths also mutate a per-level
engine's frame plan and shared uniforms, making naive reentrant drawing unsafe.

IP deliberately uses Create's vanilla block/entity/contraption renderers during
portal, alternate-world (including third-person portal camera), and clipped
cross-portal entity views. Both Flywheel and Create query
`VisualizationManager.supportsVisualization`, so one scoped decision enables
vanilla drawing while preventing Flywheel engine dispatch for that view.

A main-world view containing a portal-colliding entity also uses vanilla for
its entire duration. This decision is captured before Flywheel starts the view,
using the same enablement and current-level collision data as IP's entity
clipping. Otherwise an entity's temporary clip flag could enable its vanilla
draw, then disappear before Flywheel's later `afterEntities` draw, showing a
second, whole, unclipped visual. Immutable thread-local view scopes restore
parent decisions even on exceptions and do not affect chunk workers.

Outside those render-thread scopes, Flywheel's original result is preserved:
the selected instancing, indirect or off backend is not changed, and existing
managers/visuals are not reset. The predicate has no mutable backend toggle or
nesting counter that could leak on exit. Worker-thread visual registration does
not inherit the render thread's temporary fallback.

Flywheel normally removes visualized block entities during chunk compilation.
That cannot work with a draw-time fallback: the same compiled chunk can be
rendered both outside and inside a portal. IP keeps the entries after the visual
is registered and applies the normal visualization/skip check at the vanilla
block-entity dispatcher instead. This covers vanilla's SectionCompiler,
Embeddium's ChunkDataBuiltEvent and Sodium's render predicate, which all use
`VisualizationHelper.tryAddBlockEntity`. Ordinary main-view draws are not
rendered twice; portal draws use IP's already-clipped vanilla shaders. Crumbling
uses the same retained entries in fallback views and Flywheel in the main view.

## Verification boundaries

`OptionalGameTestDiscoveryTest` exercises holder loading/reflection with optional
mods and typed implementations blocked; QIO's implementation is dispatched only
when both Sable and Mekanism are present. QIO's ten phase tests remain in its
separate implementation, under the existing release-excluded `sablee2e` package.

`FlywheelPortalFallbackTest` checks scope decisions, retained-entry/suppression
pairing, removal of obsolete hooks, and exceptional context restoration. These
are not rendered correctness tests.

The graphical matrix must capture actual framebuffer geometry/motion on both
sides of portals, nested views, crumbling, remote creation, and resource reload.
`PortalSmokeCreateScene` builds motor/shaft/cog and rotating bearing fixtures;
its backend evidence reports configured and actual backend IDs, instantiated
main-view engine type, observed fallback decisions and restored nested contexts.
An active backend request must not pass as off. Hardware/driver limitations must
be reported separately from passing rendered checks.

## Reproducible source artifacts

- [Create 6.0.10-280 sources](https://maven.createmod.net/com/simibubi/create/create-1.21.1/6.0.10-280/create-1.21.1-6.0.10-280-sources.jar),
  SHA-256 `376de15ca5acf720106a075ca4eb2ef53e63e0e5d9ec93523a1abbdd0f9f0cb4`.
- [Flywheel 1.0.6 sources](https://maven.createmod.net/dev/engine-room/flywheel/flywheel-neoforge-1.21.1/1.0.6/flywheel-neoforge-1.21.1-1.0.6-sources.jar),
  SHA-256 `c251479dea729a568bedee28bb68021276f4b49be0a5b6a9603501729d5dfbfe`.
- [Sable 2.0.5 sources](https://maven.ryanhcode.dev/releases/dev/ryanhcode/sable/sable-neoforge-1.21.1/2.0.5/sable-neoforge-1.21.1-2.0.5-sources.jar),
  SHA-256 `6c9e64436b1fec3f3978d7029029d3516697d7b9fed08c085228bb13b88734bf`.

The Flywheel binary's SHA-256 is
`31dda15c205eb596d3b3449ef03f6af7363a6cd35b3da4bfe916b304f9e5337e`.

Worker-thread visual registration uses the physical player's level instead of
IP's temporarily swapped `Minecraft.level`. Render-thread checks retain the
actual view level; with no player the original identity is preserved. Flywheel's
backend/special-level checks and transaction queues remain unchanged.
