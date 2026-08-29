# Immersive Portals - CE

> **Notice:** **Immersive Portals - CE** is an independently community-maintained distribution of Immersive Portals for NeoForge. It is not the official Immersive Portals release.

Immersive Portals adds see-through portals and seamless travel between dimensions. Portals can be nested, transformed, scaled, and used to build non-Euclidean spaces without loading screens.

This repository maintains the Minecraft 1.21.1 NeoForge build derived from [qouteall's Immersive Portals](https://github.com/iPortalTeam/ImmersivePortalsModForNeo). The original project and its contributors remain the foundation of this distribution.

## Features

- See through portals before entering them.
- Travel between dimensions without a loading screen.
- Render portals inside other portals.
- Build mirrors, wrapping worlds, dimension stacks, and custom portal networks.
- Transform player scale and gravity direction through compatible portals.
- Use commands, datapacks, and APIs to create custom portal behavior.
- Run alongside Sable 2.0.5 with a verified single-pass collision integration.

## Minecraft and loader support

- Minecraft 1.21.1
- NeoForge 21.1.228 or newer
- Java 21

The Fabric project is maintained separately by the original Immersive Portals team. This repository produces the NeoForge jar only.

## Sable compatibility

Sable is optional. When Sable 2.0.5 is installed, the portal collision wrapper and Sable's entity-collision redirect compose without executing the portal collision hook twice. A dedicated NeoForge GameTest launches the transformed game, moves a real entity, and verifies both systems execute exactly once as intended.

Without Sable, normal Immersive Portals collision behavior is unchanged.

## Building

Use Java 21:

```powershell
.\gradlew.bat test runGameTestServer build
```

The runnable jar is written to `build/libs/immersive_portals-<version>.jar`.

## Releases and support

- [Version changelogs](changelog/)
- [GitHub releases](https://github.com/UpperMoon0/ImmersivePortals/releases)
- [Issue tracker for this build](https://github.com/UpperMoon0/ImmersivePortals/issues)
- [Upstream Immersive Portals wiki](https://qouteall.fun/immptl/)
- [Official NeoForge CurseForge project](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-for-forge)

## Attribution

Immersive Portals was created by qouteall and is licensed under Apache-2.0. This distribution preserves the upstream license and attribution and includes additional compatibility, performance, testing, and release-maintenance changes.
