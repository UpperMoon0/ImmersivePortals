# Changelog

All notable changes to this project will be documented in this file.  
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project tries to adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased Changes]

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

[Unreleased Changes]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.9...HEAD
[6.0.9]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.8...v6.0.9
[6.0.8]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.7...v6.0.8
[6.0.7]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.6...v6.0.7
[6.0.6]: https://github.com/UpperMoon0/ImmersivePortals/compare/v6.0.3...v6.0.6
[6.0.3]: https://github.com/UpperMoon0/ImmersivePortals/releases/tag/v6.0.3
