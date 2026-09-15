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
- Run alongside Sable 2.0.5 with seamless, verified portal handoff for moving sublevels, riders, gravity, and remote tracking.

## Minecraft and loader support

- Minecraft 1.21.1
- NeoForge 21.1.228 or newer
- Java 21

The Fabric project is maintained separately by the original Immersive Portals team. This repository produces the NeoForge jar only.

## Sable compatibility

Sable is optional. With Sable 2.0.5 installed, this build integrates moving sublevels with Immersive Portals rather than treating them as ordinary hidden-world entities. Sublevels keep one global plot identity while crossing dimensions, retain velocity and interpolation history, preserve rider/passenger relationships, and continue remote entity tracking on the opposite side of a portal. Vertical dimension stacks, rotated portals, gravity-driven recrossing, server-first rider teleports, and client camera/gravity transforms are covered by automated tests.

The collision wrapper also composes with Sable's entity-collision redirect without executing the portal collision hook twice. The dedicated graphical E2E runs the same crossing scenario over both Sable UDP networking and the TCP fallback and rejects duplicate source/destination client copies that overlap beyond the handoff budget.

Without Sable, normal Immersive Portals collision behavior is unchanged.

## Building and verification

Use Java 21. The canonical verification harness is cross-platform:

```text
python tools/verify.py core   # build + JUnit + NeoForge GameTests
python tools/verify.py e2e    # dedicated server + real graphical client
python tools/verify.py full   # all layers
```

`core` runs the Python harness/policy regression tests, Java build and JUnit tests, and NeoForge GameTests. `full` runs `core`, Sable dedicated E2E, then the default Sodium visual test. Plain Gradle `build` only includes JUnit; `coreCheck` adds GameTests but not Python or graphical tests.

The Sable E2E drives a tall physics body and a real Create seat through Overworld -> Nether -> Overworld, allows a gravity-driven recross, and exercises client dismount. Server and client independently verify the entity/passenger graph. All five client phase markers, both pass markers, zero client exit status, and clean critical-runtime checks are required.

The visual test creates a red source wall and green destination wall connected by a portal. A second red destination wall lies in front of the destination clipping plane and must not appear. It requires actual portal rendering and matching green framebuffer pixels over multiple frames, both before and after resource/shader reload. Screenshots are retained. This is a targeted clipping regression, not exhaustive visual coverage or an Iris shaderpack test.

```text
python tools/verify.py visual                       # Sodium, Sable present
python tools/verify.py visual --renderer vanilla --no-sable
python tools/verify.py visual --renderer iris
python tools/verify.py visual --renderer veil
python tools/verify.py matrix --samples 1200        # all five configurations, longer runtime samples
```

Visual runs measure real server tick processing and client frame rendering after scene warmup. They require at least 200 samples (1200 in nightly runs), server p95 <= 100 ms and client p95 <= 250 ms. These conservative shared-runner budgets detect gross regressions; they are not hardware-independent FPS guarantees. Reports also record maximum duration and heap usage. The former fixed-count/synthetic-loop "benchmarks" were removed; their useful mixin contract check remains in JUnit.

### CI policy

- Every branch push: core checks.
- PRs targeting `main`: core checks plus Sable E2E and visual checks for every non-documentation change. Unknown paths require heavy checks. Documentation-only PRs skip graphics.
- Every push to `main` and manual CI run: core, Sable E2E and visual checks.
- Nightly/manual extended workflow: vanilla, Sodium, Iris, Veil, and vanilla without Sable, with 1200 runtime samples each.
- Release: core, Sable E2E and all five visual configurations must pass before publishing the jar produced by core verification.

Configure branch protection to require **Required verification**. This stable aggregate job fails if any selected test failed, was cancelled or unexpectedly skipped. A workflow file alone cannot configure repository branch protection.

After successful push CI on `main`, a version change creates a tag at that verified commit and explicitly dispatches Release. Tags pushed with `GITHUB_TOKEN` do not automatically trigger another push workflow. Manual Release dispatch accepts an existing version tag, including for retrying a failed publication. Tags pushed by a user still trigger Release directly.

The local Aeronautics/Simulated/Offroad sibling jars are confined to the manual development client. Clean CI and verification runs do not silently pick them up. Automating that private/local stack in hosted CI requires reproducible downloadable artifacts or a source-build recipe; Veil itself is already bundled inside Sable 2.0.5 and is exercised by the default graphical runs. The matrix additionally tests standalone Veil without Sable.

Use Java 21 (`JAVA_HOME` if the default Java differs). Linux without a display needs `xvfb-run` and Mesa (`xvfb libgl1-mesa-dri` on Ubuntu); Windows needs a logged-in graphical desktop. No manual game interaction is needed. Local runs are sequential to limit memory pressure.

Only one harness may run per checkout. Its disposable `run-sable-e2e-*` directories must not contain personal worlds or settings. Fresh worlds and result markers prevent stale passes. Reports live in `build/verification-<mode>.json`, Sable evidence in `build/sable-dimension-stack-e2e/`, and visual evidence in `build/portal-visual-<renderer>-<sable|no-sable>/`. CI preserves evidence on failure. Run Python regressions alone with `python -m unittest discover -s tools -p "test_*.py"`.

The runnable jar is `build/libs/immersive_portals-<version>.jar`. Dedicated E2E/visual driver classes are excluded from that jar.

## Releases and support

- [Version changelogs](changelog/)
- [GitHub releases](https://github.com/UpperMoon0/ImmersivePortals/releases)
- [Immersive Portals - CE on CurseForge](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-ce)
- [Issue tracker for this build](https://github.com/UpperMoon0/ImmersivePortals/issues)
- [Upstream Immersive Portals wiki](https://qouteall.fun/immptl/)
- [Official upstream NeoForge CurseForge project](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-for-forge)

## Attribution

Immersive Portals was created by qouteall and is licensed under Apache-2.0. This distribution preserves the upstream license and attribution and includes additional compatibility, performance, testing, and release-maintenance changes.
