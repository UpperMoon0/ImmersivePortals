# Issue acceptance map for 6.0.13 / PR #23

Issues close on merge through PR closing references. Current-head native CI must
pass before merge; historical green results are not substituted for the new head.
The renderer items below were implemented in earlier main commits and are retained
and revalidated here. The chunk/receiver follow-ups are new fixes in this PR.

| Issue | Implementation and regression coverage | Required native verification |
| --- | --- | --- |
| #7 Embeddium adapter | Independent backend selection, Embeddium renderer/context adapters; `RendererCompatibilityTest`, `EmbeddiumTargetContractTest`, `EmbeddiumRebuildLifecycleTest`, nested data/mesh-halo tests | Embeddium, NeOculus, vanilla and Sodium portal/clip/nest/cross/reload lanes |
| #8 NeOculus integration | NeOculus-specific shader adapter and strict backend ABI checks; `NeOculusTerrainClippingTest`, dimension-state and reload-provider tests | NeOculus active/off, both real packs, official Iris/Sodium preservation |
| #9 Shader clipping paths | Terrain solid/cutout/translucent, entity/block-entity/particle and final geometry-stage transformations; `ShaderClippingTransformationTest`, clipping policy/provider tests; [audit](../misc/iris-clipping-audit.md) | Per-program retained/excluded positives, targeted negative controls, reload/toggle, mirrors/nesting, real packs and shadow controls |
| #10 Active-pack matrix | Exact staged fixture/pack and live pipeline assertions, nine backend/Sable pairs, independent shaders-off lanes; Python evidence rejection regressions | All 30 graphical configurations; active-pack lanes and deliberate pack/clipping failures |
| #11 Copy-image fallback | Capability/format/sample/dimension copy planning and state-preserving framebuffer blits; `FramebufferCopyPlanTest` | Compatibility/debug, forced blit and actual GL3.3 without copy-image, resize/reload and pixel/depth checks |
| #13 Modern Flywheel | Modern 1.0.6 lifecycle with scoped vanilla portal/clipped fallback and main-view restoration; `FlywheelRenderScopeTest`, `FlywheelPortalFallbackTest`; [audit](compat/flywheel-1.0.6.md) | Off/instancing/indirect actual-backend checks, rotating fixtures, nested context restoration, crumbling clean/damaged/restored/clipped and reload |
| #15 End chunk recovery | Retained tickets cannot trigger vanilla status transitions, so failed promotions are requeued and rebuilt with cooldown. Pending sends request recovery without dropping records. `ChunkLoadingRecoveryTest`; Python requires complete recovery evidence | Every real-pack lane injects two failed End promotion futures, sends a client chunk unload, waits for recovery/resend, then requires unchanged nested backdrop/depth and later reload/toggle/crossing checks |
| #21 Receiver races | Concurrent native/receiver maps; per-map registration synchronization with upstream duplicate/replacement behavior and lock-free dispatch/removal. Exact 4.2.2+a92978fd19 gate. Actual upstream handler concurrency tests; [contract](compat/fabric-network-registration.md) | Core channel enumeration, UDP/TCP login, shader negative controls and full graphical matrix |

## Chunk recovery source contract

Minecraft 1.21.1 `ChunkHolder.updateFutures` starts accessible, block-ticking and
entity-ticking promotions only when ticket status crosses the corresponding
threshold. `ChunkMap.getChunkRangeFuture` returns an unsuccessful result when a
neighbor is absent or unloaded. A failed promotion can therefore remain failed
under an unchanged portal ticket. Retrying only the ticket insertion is ineffective.

CE uses the updating holder map while ticket propagation is in progress, retains
pending chunk sends, and schedules failed promotions again after 20 game ticks.
Deferred retries release throttle slots so other chunks can load, and duplicate
pending-send requests cannot keep extending that cooldown. Retries invoke vanilla
preparation, full-state confirmation and save-dependency handling on the server
thread. Only statuses permitted by the current ticket are retried; successful
or pending futures are preserved and exceptional I/O remains visible.

The development-only `PortalSmokeChunkRecoveryProbe` waits for a genuinely loaded
End watch, unloads client chunk `(0,-1)`, replaces the real holder's two ticking
futures with failed neighbor-range results, and returns its real watch record to
the pending-send queue. It withholds scene readiness until both futures and actual
packet delivery recover. Native block/light data, mesh prerequisites and rendered
pixels are still checked by the existing real-pack pixel/depth oracles. Probe
classes are excluded from the published jar.

The conditional full-session retry is removed. Each real-pack lane must pass its
first session, including the injected recovery; merge remains gated on all four.
