# Changelog

All notable changes to this project will be documented in this file.  
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project tries to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased Changes]

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

[Unreleased Changes]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.11...HEAD
[6.0.11]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.10...v6.0.11
[6.0.10]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.9...v6.0.10
[6.0.9]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.8...v6.0.9
[6.0.8]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.7...v6.0.8
[6.0.7]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.6...v6.0.7
[6.0.6]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.3...v6.0.6
[6.0.3]: https://github.com/UpperMoon0/ImmersivePortals/releases/tag/v6.0.3
