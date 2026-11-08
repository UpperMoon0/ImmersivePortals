# Valkyrien Skies 2 / Clockwork compatibility — NeoForge 1.21.1

## Release status: NOT SUPPORTED

This is an implementation workstream, not a completed compatibility certification.
**Do not publish a claim that VS/Clockwork ships traverse IP portals.** The
optional read-only probe and pure transaction contract implemented here are the
start of the work, not a native ship handoff. The existing Sable integration
must remain unchanged until the separate VS lanes are proven.

### Source and exact version boundaries

- IP CE: Minecraft 1.21.1, NeoForge 21.1.228 baseline, Java 21.
- Official VS2: `ValkyrienSkies/Valkyrien-Skies-2`, `1.20.1/main`,
  Forge/Fabric, `2.4.12` in the examined Gradle metadata.
- Official Clockwork: `ValkyrienSkies/Clockwork`, `1.20.1/main`,
  Forge/Fabric, `0.5.7` in the examined Gradle metadata.
- Existing 1.21.1 VS source candidate:
  `Shiraishi-Sayuki/Valkyrien-Skies-2-1.21.1`, `1.21.1/main`,
  `2.3.0-beta.8` in Gradle; **its native `handleShipPortals()` and
  `shipChangeDimension()` implementations are commented out**.
- Unofficial 1.21.1 Clockwork binary: Soleid's 0.1.13-based NeoForge
  port advertises Sable and VS backends. Do not confuse that historical
  feature set with upstream Clockwork 0.5.x.

Pin source commits, binary URLs and SHA-256 hashes for each candidate before
using it in CI. Rebuild from reviewed source or verify the exact binary's
provenance. Confirm licenses and dependencies, and test the NeoForge baseline
upgrade rather than silently changing it.

### Implemented experimental integration (not yet release-ready)

A pinned API adapter (`VSNativeShipTransport`) now calls the 1.21.1 VS
`ServerShipWorldCore.teleportShip` API with a transformed ship pose,
rotation, linear velocity, angular velocity and destination dimension. It
records pending native ownership, de-duplicates in-flight portal crossings,
and reports unconfirmed transfers rather than copying blocks. It is disabled
by default and can be enabled only in an isolated integration test with
`-Dip.vs.enableNativeShipTeleport=true`.

**Occupants intentionally block movement:** the adapter currently refuses
ships with players near their physics AABB because no ordered rider/seat
handoff is implemented. This is NOT passenger compatibility. Joint-dependent
ships, connected constructs and rollback are also NOT certified.

`VSRemoteShipyardTracking` supplies bounded, per-player hidden shipyard
chunk loaders when a remote viewer actually watches the ship's logical
world-space chunks through a portal. It retains and releases per-ship loader
ownership; the VS native ship-metadata protocol still needs runtime proof.

`VSVisibleShipSections` repairs a specific vanilla rendering conflict:
Immersive Portals cancels normal `setupRender` before the VS fork's
`vs$addShipVisibleChunks` RETURN hook. The remote-view path now invokes
that collector itself before cancelling, and disconnect cleanup drops its
cached reflection target. This does not certify the Sodium/Iris paths.

### Runtime hook and boundary geometry now present

`ValkyrienSkiesShipProbe` is an **opt-in, read-only** native
inspection path: `-Dip.vs.shipProbe=true`. It observes actual VS ship
transforms and shipyard AABB data on portal ticks, determines dimension
ownership, and applies a sweep of 15 hull boundary witnesses against the
real portal aperture. In probe mode there is no teleport mutation or persistent data
rewrite. The separate experimental native adapter above is the only path
that changes ship physics ownership. Without VS installed, the normal runtime takes no VS path.

`ShipPortalSweep` rejects non-finite transforms and aperture misses rather
than taking the ship center of mass as proof of fit. This is still only a
**candidate detector**. Complex non-convex hulls, changing joint shapes,
portal scale/mirror transforms and high-speed motion need their own tests.
The swept-path predicate must not be mistaken for a complete swept-volume
collision solver.

`ShipTransitCoordinator` is a pure server-thread-only transaction
contract that rejects recursive/duplicate transfers, validates an adapter's
prepare/commit/confirm/rollback sequence and halts on uncertain native
ownership. **The transaction contract is not yet wired into the asynchronous VS adapter.**
That remains required because: the target 1.21.1 ABI and genuine rollback semantics
have not been verified, and a fake rollback implemented as block copying
would cause duplicate physics ship ownership.

### Remaining work for production-quality native transfer

1. Select a pinned 1.21.1 VS port with a verifiable source-to-binary
   relationship; pin Clockwork, Create, loader and physics-core versions.
2. Register an optional VS backend with no class linkage in an IP-only
   installation. Identify the active backend before touching any ship.
3. For each active ship, retain *previous and current native physics poses*,
   its unique ship ID, native dimension/shipyard chunk claim, active chunks
   and the complete joint/dependent-ship graph. Use substep-aware swept
   geometry, not tick-end COM alone.
4. Resolve the native portal intersection and reject missing/undersized
   aperture, invalid/scaled transforms unsupported by VS, unloaded or
   colliding destination shipyard allocations, and overlapping claims.
5. Prepare a correlated server-first handoff for *all* standing players,
   mounted seat graphs, loose entities and dependent vessels. Freeze only
   the bounded client prediction interval; preserve world-space positions,
   look directions and velocities.
6. Stage destination chunks and native ship attachment state, including
   Clockwork propellers, balloon gas/heat, bearings, kinetic controllers,
   inventories and transient force callbacks.
7. Call the pinned port's native `ShipWorld.teleportShip` or other
   documented native transaction (never reassemble blocks by hand).
   Preserve immutable ship ID, transforms, linear and angular velocities,
   source/destination dimension identity, and body ownership.
8. Only acknowledge players after native destination synchronization and
   exact client chunk/mesh readiness. Confirm unique destination ownership
   on **both** server and client, plus retained state of cargo and joints.
9. On failure, use native rollback of the same ship; on unresolved ownership,
   fail closed and report a hard error instead of duplicating objects.
10. Clean up reverse motion, cooldowns, clients that disconnect/reconnect,
    departed dimensions, unloaded shipyard chunks, and shader render owners.

The official 1.20.1 VS2 implementation's `ChunkManagement.tickChunkLoading`
issues native watch/unwatch tasks bound to shipyard chunks. The examined
1.21.1 port similarly uses `ChunkMapAccessor.callMarkChunkPendingToSend`
against the source dimension. The IP remote-view bridge must send dimension-
addressed chunk streams and correctly retire the remote plot only when the
corresponding physics world/ship is gone. Merely making a remote ship's
transform visible will still leave missing or stale blocks.

For visuals, investigate `VSClientGameUtils.transformRenderIfInShipyard` and
`getClientShip`: both consult `Minecraft.getInstance().level`. IP's
`ClientWorldLoader` temporarily swaps that level for remote rendering,
but VS's global client ship-world and view-area caches need explicit proof
for both dimensions in a nested portal scene. Do not assume a successful
screenshot without shader clipping, frustum and owner-lifetime evidence.

### Mandatory automated acceptance

The receipt contract in `tools/verify_vs_clockwork_evidence.py` requires:
- A real dedicated server and real graphical client, pinned artifacts,
  exact SHA-256 digests and matching run/commit IDs.
- Control and 3500+-block large ships in ordinary, 90-degree rotated,
  dimension-stack and vertical broadside routes, each with outbound/return
  crossings and native physics-driven motion.
- Retained unique native ship ID, block manifest, block entities, cargo,
  velocity, angular velocity, passengers, client chunks/meshes, post-handoff
  rendered frames, and save/reload continuity.
- Active Clockwork propellers, balloons, gas, bearings and Create kinetics,
  with operational checks **after** the dimension change.
- Shader-on, shader-off, normal viewport and nested portal views, including
  negative controls for wrong-world data and failed render cleanup.
- Bound network delay/loss, death/dismount/reconnect, failed destination,
  rollback, disabled-mod, native backend and Sable backend/coexistence lanes.

The evidence validator is only a schema/negative-control test. It does not
produce game receipts or establish their provenance. A mandatory, non-skippable
graphical E2E runner must be implemented and pass before removing this file's
**NOT SUPPORTED** status or changing release notes.

### Local verification

```sh
python3 -m unittest discover -s tools -p 'test_verify_vs_clockwork_evidence.py' -v
./gradlew test --tests 'qouteall.imm_ptl.core.compat.valkyrienskies.*'
# Only with an installed VS2 1.21.1 port:
# -Dip.vs.shipProbe=true  (diagnostic; NEVER enables ship teleport)
```

Do not run `tools/verify_vs_clockwork_evidence.py` against fabricated
receipts and report a passing integration. Real E2E is a separate gate.
