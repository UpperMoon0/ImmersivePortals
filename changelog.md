# Changelog

All notable changes to this project will be documented in this file.  
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project tries to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased Changes]

## [6.1.4] - Unreleased

### Fixed

- Detect swept leading-hull entry at dimension stacks before Sable ceiling atmosphere reaches zero; preserve immediate reversals before COM entry.
- Coordinate dimension-stack crossings for players standing on Sable ships, including envelope and deck passengers.
- Preserve standing support through body migration and bound client prediction while destination support synchronizes.
- Preserve client-request handoff identity between the authoritative dimension packet and acknowledgement, preventing reverse prediction from replacing pending standing support.
- Require correlated successful attachment, exclusive ownership and continuous chunk readiness; verify remote cold-start TCP flights alongside the prepared-terrain UDP baseline.
- Keep ordinary same-dimension portal traversal available while standing on a ship; existing support alone is not evidence of a completed migration.
- Preserve Aeronautics balloon gas across transactional ship migration instead of rebuilding a cold balloon.
- Add real unmounted envelope/deck burner stack regressions without command force-loading; reuse the powered UDP session while retaining a separate TCP lane.

## [6.1.3.1] - Unreleased

### Fixed

- Fix startup with Sable when Mixin resolves an interpolation diagnostic helper excluded from the release jar. Keep the preservation oracle in the development test runtime behind a packaged observer interface.

### Verification

- Scan every class in the actual release jar for references to excluded GameTests, with a negative control reproducing the missing helper reference.

## [6.1.3] - Unreleased

### Fixed

- Preserve explicit dimension provenance for Sable UDP snapshots and interpolation-clock updates across dimension-packet ordering; negotiate native UDP support and retain unchanged-wire TCP fallback with older peers.
- Evaluate retained Sable rider portal approach at the authoritative mounted eye rather than the seat origin, so roll/pitch does not incorrectly reject inward eye motion or authorize outward eye motion. Keep the existing stale-request guards.

### Verification

- Add angular point-velocity regressions, compiled production mount-traversal/anchor contracts, and a native rigid-body guard GameTest using actual direct and nested mounts. The native guard test is not an end-to-end rolling-flight or network-handoff certification.

See [`changelog/6.1.3.md`](changelog/6.1.3.md) for patch scope and verification limits.

## [6.1.2] - Unreleased

### Fixed

- Synchronize Create machinery and block updates through the actual Sable plot watcher list.
- Preserve whole-ship and retained-rider continuity while rejecting stale reverse-transfer requests; cover bounded animated-portal and exact-plane crossings.
- Restore complete occupied ship boundary meshes and prevent stale terrain-graph callbacks during dimension cleanup.
- Preserve fresh native Sable collision state, inherited motion and nested-move composition around portal terrain collision.

### Changed

- Add genuinely assembled multi-chunk ships, working machinery and cargo, repeated ordinary/stack crossings, and vertical broadside outbound/return coverage over UDP and TCP.
- Add pinned real Aeronautics propulsion lanes and strict full-hull, rider, cargo, kinetic and framebuffer evidence.
- Require all six ship lanes in the 47-job CI/release contract without weakening existing renderer/network checks.

See [`changelog/6.1.2.md`](changelog/6.1.2.md) for patch notes and explicit compatibility limits.

## [6.1.1] - 2026-10-13

### Fixed

- Support structurally compatible newer Forgified Fabric Networking API modules, including TxniLib's 4.3.0 and current 1.21.1 4.3.1 releases. Validate injection targets instead of rejecting an exact version mismatch. Add real-jar contracts, receiver concurrency checks, and native UDP/TCP compatibility lanes.
- Hold local movement prediction for a bounded, captured Sable rider while detach, authoritative dimension and seat-attachment packets are in flight; prevent client free-fall during a delayed handoff without loosening continuity assertions. Add a deterministic 40-tick native detached-rider probe to both UDP and TCP E2E lanes.

### Changed

- Expand detached-rider native regressions to acknowledgement rejection, cancellation, replacement players, death, unrelated seats/dimensions, post-Ack seat timeout, cleanup and disconnect. Add port-scoped latency/jitter/loss lanes without relaxing gameplay assertions.
- Require all 41 CI checks for release evidence, including eight native transport/API/network lanes and the existing 30 renderer/shader configurations.

See [`changelog/6.1.1.md`](changelog/6.1.1.md) for the complete patch release notes.

## [6.1.0] - 2026-10-11

### Added

- Add an opt-in operator prototype for stationary miniature views of existing buildings, with owner-controlled create/list/pickup/place/restore commands and fractional scale divisors from 2 to 32.
- Persist backing references without copying, moving or deleting blocks, inventories or occupants; repair missing portal faces and quarantine invalid registry data.
- Add lifecycle, native persistence, 32-view chunk-loading and diagnostic renderer regressions.

### Fixed

- Preserve every in-range global portal's destination loader when ordinary portal entity queries exceed their overflow threshold.
- Await NeoForge saved-data IO before test-fixture cleanup and reject asynchronous cleanup/shutdown errors after GameTest success.

This is the first, stationary prototype milestone of issue #24. There is no physical anchor item, automatic building relocation, moving Sable miniature, or scale-changing Sable handoff. See [miniature base usage and limitations](docs/miniature-bases.md).

## [6.0.13] - 2026-10-09

### Fixed

- Requeue failed portal chunk promotions with a bounded cooldown and recreate failed vanilla futures under retained tickets; pending chunks remain queued for real block/light delivery.
- Make Forgified Fabric native-handler and receiver maps safe for concurrent reads, registration and removal. Preserve duplicate-return and replacement semantics for both packet flows and local listeners.

### Changed

- Overlap independent shader-pack and fixture checks, preserve exact main-tested release artifacts, and reuse only complete same-commit verification evidence before publication.
- Require fault-injected End chunk recovery and resend in every real-pack lane; report scene progress and heartbeat diagnostics.

See [`changelog/6.0.13.md`](changelog/6.0.13.md) and the [issue acceptance map](docs/issue-resolution-6.0.13.md).

## [6.0.12] - 2026-10-09

### Fixed

- Synchronized NeOculus texture metadata with texture allocation and deletion, fixing stale depth dimensions after shader reload while preserving cached lookups.
- Prevented parallel Forgified Fabric native receiver registration from corrupting NeoForge payload registries or leaving the network setup phase open after a registration failure.
- Graphical verification now reports unexpected disconnects immediately and reads newly appended server-log data, preserving incomplete lines and normal shutdown behavior.

### Changed

- Prepared a fresh release version containing the renderer compatibility improvements from 6.0.11 and the follow-up reload and login fixes.

See [`changelog/6.0.12.md`](changelog/6.0.12.md) for the complete release notes and compatibility limits.

## [6.0.11] - 2026-10-08

### Added

- Independent terrain-renderer and shader detection, including pinned Embeddium 1.0.15 and NeOculus 1.8.7 integration alongside Sodium 0.8.12 and official Iris 1.8.14-beta.1.
- Active shader, shadow, real-pack, lower-capability OpenGL and Flywheel graphical regression lanes, with dependency ABI checks and failure diagnostics.

### Fixed

- Portal shader clipping across world programs, shader reloads and disabled-shader paths, while preserving shadow-pass clipping state.
- Recursive terrain render lists and Sodium multi-draw batches, mesh prerequisite chunk halos, and nested destinations under server performance limits.
- Sodium block updates arriving between initial mesh submission and upload after a resource reload.
- NeOculus early connection initialization and framebuffer depth/stencil copying with a framebuffer-blit fallback when copy-image is unavailable.
- Create/Flywheel portal rendering through a scoped vanilla fallback that restores the selected main-view backend.
- Optional QIO GameTest discovery without Sable and exclusion of development verification hooks from release jars.

### Changed

- Documented supported renderer combinations, shaderpack coverage limits and the full CI/release acceptance matrix.

See [`changelog/6.0.11.md`](changelog/6.0.11.md) for the release notes and compatibility limits.

## [6.0.10] - 2026-10-01

### Fixed

- Cached OpenGL buffers and vertex arrays now initialize objects on drivers supporting direct state access, fixing Veil's unknown-object debug labels and failed particle buffer uploads. Older OpenGL contexts retain the existing allocation path.
- Sable verification now runs physics fixtures near the origin, measures gravity and drag over a complete simulation interval, inserts items through the public inventory API, and snapshots sided inventories immediately before assembly.
- Prevented Create 6.0.10 item drains from duplicating their held item into a dropped entity during Sable assembly.

### Changed

- Core verification rejects optional GameTest failures instead of reporting a successful run with failed Sable checks.
- Added a graphical regression covering cached object initialization, cache refill, immediate debug labels, and direct-state-access uploads.

See [`changelog/6.0.10.md`](changelog/6.0.10.md) for the release notes.

## [6.0.9] - 2026-09-15

### Added

- Seamless Sable 2.0.5 cross-dimension sublevel and rider handoff with automated UDP/TCP graphical E2E coverage.
- Portal clipping and renderer-matrix visual regression coverage, including Veil compatibility.
- Canonical verification, nightly checks, and hardened tag/release automation.

### Fixed

- Sable sublevels disappearing, flickering, sticking at dimension-stack boundaries, or leaving duplicate client copies during migration.
- Sable rider camera/gravity transform and server-first acknowledgement ordering across rotated portals.
- Portal geometry rendering behind the active clipping plane.
- Published NeoForge module identities and packaged mod icon metadata.

### Changed

- Sable migration is transactional and explicitly orders destination pre-sync before source retirement.
- Sable tracking and packet redirection now preserve the logical remote world through handoff.

See [`changelog/6.0.9.md`](changelog/6.0.9.md) for the complete release notes.

## [6.0.8] - 2026-08-29

### Added

- Real NeoForge GameTest coverage for the Sable 2.0.5 portal collision integration.
- Deterministic collision-wrapper contract tests.
- Automated GitHub and CurseForge release publishing workflows.

### Changed

- Updated the NeoForge development baseline to 21.1.228 for Sable 2.0.5.
- Isolated clean-runner dependency resolution for Sable's embedded libraries.

### Fixed

- Prevented duplicate portal collision processing when Sable is installed while preserving normal behavior without Sable.

## [6.0.7] - 2025-06-18

### Fixed

- Oritech animations not displaying ([#13](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/issues/13)).
- ComputerCraft monitors not displaying anything ([#31](https://github.com/iPortalTeam/ImmersivePortalsModForNeo/issues/31)).

## [6.0.6] - 2024-12-22

### Updated

- Sync upstream (v6.0.6)
- Sodium compat (v0.6.0)
- Iris compat (v1.8.0) (experimental)

### Fixed

- Default config values being wrong

## [6.0.3] - 2024-10-20

### Added

- Initial port to NeoForge 1.21.1

### Known Issues

- Iris compatibility is not fully functional
- Crash with SecurityCraft

[Unreleased Changes]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.1.1...HEAD
[6.1.3.1]: changelog/6.1.3.1.md
[6.1.3]: changelog/6.1.3.md
[6.1.2]: https://github.com/UpperMoon0/ImmersivePortals/pull/30
[6.1.1]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.1.0...v6.1.1
[6.1.0]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.13...v6.1.0
[6.0.13]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.12...v6.0.13
[6.0.12]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.11...v6.0.12
[6.0.11]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.10...v6.0.11
[6.0.10]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.9...v6.0.10
[6.0.9]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.8...v6.0.9
[6.0.8]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.7...v6.0.8
[6.0.7]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.6...v6.0.7
[6.0.6]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.3...v6.0.6
[6.0.3]: https://github.com/UpperMoon0/ImmersivePortals/releases/tag/v6.0.3
