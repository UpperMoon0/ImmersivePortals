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

## Building and verification

Use Java 21. The canonical verification harness is cross-platform:

```text
python tools/verify.py core   # build + JUnit + NeoForge GameTests
python tools/verify.py e2e    # dedicated server + real graphical client
python tools/verify.py full   # all layers
```

`core` is the fast local gate. `full` is the authoritative all-tests gate used for complete validation. CI runs the same `core` and `e2e` layers in parallel to minimize wall-clock time while preserving full coverage. The E2E harness uses fresh result markers/world state and requires both independent server and client pass markers.

The E2E run automatically joins a dedicated localhost server and drives a real Sable body through an Overworld -> Nether -> Overworld dimension stack. Server assertions cover block serialization, sublevel removal/reconstruction, finite physics velocity, vehicle identity, duplicate entity removal, and the player/passenger graph. The real client independently checks the riding graph in each dimension, acknowledges source and destination readiness, and verifies it stays mounted after returning. Both pass markers, all three client phase markers, a zero client exit code, and clean critical-runtime checks are mandatory. This covers synchronization and a live graphical runtime; it is not a pixel comparison of portal rendering or exhaustive coverage of every portal feature.

For short runs, the server reuses Gradle outputs, generates a seeded flat test world, uses low view distances, and advances on client acknowledgements instead of fixed multi-second sleeps. The graphical client retains Sodium and real rendering at a capped frame rate. Local `full` runs sequentially to limit memory pressure. No manual game interaction is needed. Linux without a display requires `xvfb-run` and Mesa (`xvfb libgl1-mesa-dri` on Ubuntu); Windows requires a graphical desktop. Set `JAVA_HOME` to Java 21 if your default Java differs.

Only one harness may run per checkout. Its disposable `run-sable-e2e-*` directories must not contain personal worlds or settings. Results and failure diagnostics are saved under `build/sable-dimension-stack-e2e/`; `build/verification-<mode>.json` records stage timings and outcomes. CI preserves logs and test reports even on failure. Harness regression tests run in the core layer and can be run alone with `python -m unittest discover -s tools -p "test_*.py"`.

For Gradle-only core verification, `./gradlew coreCheck` runs the Java build, JUnit, and GameTests. The runnable jar is written to `build/libs/immersive_portals-<version>.jar`.

## Releases and support

- [Version changelogs](changelog/)
- [GitHub releases](https://github.com/UpperMoon0/ImmersivePortals/releases)
- [Immersive Portals - CE on CurseForge](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-ce)
- [Issue tracker for this build](https://github.com/UpperMoon0/ImmersivePortals/issues)
- [Upstream Immersive Portals wiki](https://qouteall.fun/immptl/)
- [Official upstream NeoForge CurseForge project](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-for-forge)

## Attribution

Immersive Portals was created by qouteall and is licensed under Apache-2.0. This distribution preserves the upstream license and attribution and includes additional compatibility, performance, testing, and release-maintenance changes.
