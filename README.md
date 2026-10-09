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

## Renderer and shader installation

Install Cloth Config API 15.0+ for NeoForge and choose one terrain renderer. The pinned compatibility targets are:

| Terrain renderer | Optional shader implementation |
| --- | --- |
| Vanilla Minecraft 1.21.1 | None |
| Sodium 0.8.12+mc1.21.1 | Official Iris 1.8.14-beta.1+1.21.1-neoforge |
| Embeddium 1.0.15+mc1.21.1 | NeOculus 1.8.7 |

Do not install Sodium and Embeddium together. NeOculus requires Embeddium; official Iris requires Sodium. Classic Oculus is not the pinned NeOculus implementation. See the [renderer contract](docs/renderer-backends.md) for exact artifacts and version checks. NeoForge 21.1.228 is the verification baseline.

Shaderpack coverage includes MakeUp Ultra Fast 9.4a at `shadowless_high` and Complementary Reimagined r5.5.1 at `POTATO`, plus owned clipping and shadow fixtures. Other packs, presets and pack-specific shadows require separate validation. Compatibility/debug renderers retain a one-layer portal limit; normal rendering supports nesting.

With Create 6.0.10-280 / Flywheel 1.0.6, portal and clipped views use a scoped vanilla block-entity fallback, and the selected Flywheel backend resumes for the main view. See the [Flywheel compatibility audit](docs/compat/flywheel-1.0.6.md) and [6.0.12 release notes](changelog/6.0.12.md).

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

Core verification rejects optional GameTest failures too. The development GameTest server loads fixture corrections for Sable 2.0.5: tests run near the origin, gravity expectations include the configured drag and physics steps, and sided inventories are snapshotted immediately before assembly. These corrections and their mixin configuration are excluded from the release JAR.

Assembly fixtures sharing one block position run in sequence and wait for the previous assembly assertions before placing the next block. A required regression delays assembly beyond the upstream two-tick fixture spacing, and ten required QIO dashboard regressions check crafting inventory preservation across its update cycle.

The Sable E2E drives a tall physics body and a real Create seat through Overworld -> Nether -> Overworld, allows a gravity-driven recross, and exercises client dismount. Server and client independently verify the entity/passenger graph. All five client phase markers, both pass markers, zero client exit status, and clean critical-runtime checks are required.

The visual test creates a red source wall and lime destination connected by a portal. Per-program scenes place diagnostic geometry on the retained side first (a positive control), then on the excluded side of the destination plane. Entity and particle checks instead straddle the plane: a rotated thin BlockDisplay panel and enlarged stationary dust billboards keep their origins CPU-visible, while an oblique camera separates retained-red and excluded-green pixel regions. Actual CPU gate decisions, resolved shader sources and completed portal draw names/uniform states are recorded. Actual framebuffer pixels must match over multiple frames. It also requires nested portal depth, a mirror view, a real player dimension crossing observed by both processes (recording first-arrival/capture poses and stopping spectator momentum without teleporting; native wall availability and observation-aisle bounds remain mandatory), and rotating Create fixtures. A same-dimension nested Create view must restore Flywheel's renderer context and show destination motion. Create motion is measured separately in a central destination-rotor region and a right-edge source-rotor region, backed by server speed/angle/contraption evidence. A stationary cog is compared clean, stage-nine damaged and cleared without rebuilding the fixture or changing the camera, then checked on the excluded side. Its height-scaled face mask must darken directionally by more than 0.5 intensity levels after correcting against an adjacent unchanged backdrop ring; the clean image must return after clearing damage. Server and actual client damage stages are recorded for the exact cog. Debug renderer mode replaces the whole screen with the portal view, so only normal/compatibility modes require source-region pixels. The suite repeats after resource reload and resize; active shader lanes repeat after a verified shader disable/enable cycle.

`iris` and `neoculus` explicitly keep shaders off. `iris-active` and `neoculus-active` stage the repository-owned `ip-clipping-fixture-v1` pack with explicit solid, cutout, translucent, entity, block-entity and particle/fallback programs. They require the exact active pack, a live Iris rendering pipeline and the CE Iris portal renderer; stencil fallback fails. The pack and staged jars are SHA-256 recorded, alongside loaded mod versions, live GL capabilities, actual Flywheel backend, phase screenshots and pixel counts. A fixture pass is targeted regression coverage, not a claim that arbitrary artistic packs or geometry/tessellation programs are compatible. To collect real-pack evidence, pass an existing ZIP with `--shaderpack-file`; the harness records its exact filename and SHA-256 and requires that pack throughout reload/toggle. The pinned pack's supported preset is selected automatically (MakeUp `shadowless_high` or Complementary `POTATO`), or select an exact name with `--shaderpack-profile`. MakeUp 9.4a's cheaper BLOOM-disabled presets reference an undeclared `softLod` in its unconditional bloom include, so the pinned shadowless preset retains BLOOM; no pack source is patched. The expanded options and live Iris option readback are recorded; preset mismatch fails. It uses solid, nested, mirror, crossing and Create smoke scenes, without relying on the diagnostic fixture's red cutout/entity programs. Real-pack lanes capture fresh unobstructed backgrounds in each camera/dimension context every epoch: clipped views must match those pixels, while visible controls must differ. Create references retain the same source fixture and omit only destination geometry. An independent inner-world depth probe verifies the solid backdrop and nearer visible wall before shader color grading; the post-crossing view uses the run's backdrop/red-control palettes because its camera-to-wall fog distance changes. Fixed diagnostic-fixture hue assertions are unchanged. PR, nightly and release matrices run both pinned MakeUp 9.4a and Complementary Reimagined r5.5.1 on Iris and NeOculus. Official downloads are SHA-256 checked against `tools/shaderpacks/real-packs.json`; pack ZIPs are neither committed nor uploaded in evidence artifacts. These real-pack runs are additional coverage and never replace the required fixture lanes.

```text
python tools/verify.py visual                       # Sodium, Sable present
python tools/verify.py visual --renderer vanilla --no-sable
python tools/verify.py visual --renderer iris         # Iris installed, shaders off
python tools/verify.py visual --renderer iris-active  # Mandatory active fixture
python tools/verify.py visual --renderer neoculus-active --no-sable
python tools/verify.py visual --renderer iris-active --shaderpack-file /path/to/real-pack.zip
python tools/verify.py visual --renderer iris-active --negative-control pack-disabled
python tools/verify.py visual --renderer iris-active --negative-control clipping-disabled
python tools/verify.py visual --renderer iris-active --negative-control entity-clipping-disabled
python tools/verify.py visual --renderer neoculus-active --no-sable --negative-control particle-clipping-disabled
python tools/verify.py visual --renderer iris-active --render-mode compatibility --disable-copy-image
python tools/verify.py visual --renderer iris-active --render-mode compatibility --gl-context no-copy-image
python tools/verify.py visual --renderer vanilla --no-sable --flywheel-backend instancing
python tools/verify.py visual --renderer veil
python tools/verify.py matrix --samples 1200        # all nine renderer/Sable configurations
```

Visual runs measure real server tick processing and client frame rendering after scene warmup. They require at least 200 samples (1200 in nightly runs), server p95 <= 100 ms and client p95 <= 250 ms. These conservative shared-runner budgets detect gross regressions; they are not hardware-independent FPS guarantees. Reports also record maximum duration and heap usage. The former fixed-count/synthetic-loop "benchmarks" were removed; their useful mixin contract check remains in JUnit.

### CI policy

- Every branch push: core checks.
- PRs targeting `main`: core checks plus Sable E2E and visual checks for every non-documentation change. Unknown paths require heavy checks. Documentation-only PRs skip graphics.
- Every push to `main` and manual CI run: core, Sable E2E and visual checks.
- Graphical PR/main/manual CI: 26 fixture/control lanes cover the nine renderer/Sable combinations, active shaders, shadow positives/negatives, clipping negatives, compatibility/debug copy paths, and Flywheel off/instancing/indirect. Four real-pack lanes test both pinned packs on Iris and NeOculus once core and UDP/TCP login checks succeed, overlapping the fixture/control matrix. Every selected lane remains required.
- Nightly/manual extended workflow: the same 30 graphical configurations with 1200 runtime samples each. Compatibility/debug renderers have a one-layer limit, so nesting is required by normal-renderer lanes only.
- Release: core, Sable E2E and all declared renderer, active-pack, shadow, negative-control, real-pack, framebuffer-blit and Flywheel configurations must pass before publishing the jar produced by core verification. Automated releases reuse successful main CI only for the exact tagged commit: all 35 checks (including UDP and TCP) must have succeeded in the same run attempt, and its preserved jar must match the recorded SHA-256, version and provenance. No tests or sample counts are reduced. Missing, expired or ineligible evidence runs the full release matrix instead; a corrupt or missing artifact after selection fails publication.

Configure branch protection to require **Required verification**. This stable aggregate job fails if any selected test failed, was cancelled or unexpectedly skipped. A workflow file alone cannot configure repository branch protection.

After successful push CI on `main`, a version change creates a tag at that verified commit and explicitly dispatches Release with its source CI run ID. Tags pushed with `GITHUB_TOKEN` do not automatically trigger another push workflow. Manual Release dispatch accepts an existing version tag, including for retrying a failed publication; its optional `source_ci_run_id` must pass the same exact-commit/coverage checks. Omit it to verify afresh. Tags pushed by a user run full Release verification directly. The PR and main gates still run independently; prior-commit or PR evidence cannot authorize publication. The coverage contract in `tools/ci-coverage.json` is checked against CI matrix members by Python regression tests.

Graphical jobs print scene request/readiness changes and a heartbeat every minute, including elapsed time, remaining client budget and time since the last observed activity. These messages do not count as client progress or extend deadlines. This makes long real-pack runs observable without changing pixel assertions, warmup frames or timing samples. Overlapping real packs removes their wait for the slowest fixture; eligible releases avoid one entire repeated acceptance matrix. Actual savings depend on hosted-runner scheduling and graphical speed.

The local Aeronautics/Simulated/Offroad sibling jars are confined to the manual development client. Clean CI and verification runs do not silently pick them up. Automating that private/local stack in hosted CI requires reproducible downloadable artifacts or a source-build recipe; Veil itself is already bundled inside Sable 2.0.5 and is exercised by the default graphical runs. The matrix additionally tests standalone Veil without Sable.

Use Java 21 (`JAVA_HOME` if the default Java differs). Linux without a display needs `xvfb-run` and Mesa (`xvfb libgl1-mesa-dri` on Ubuntu); Windows needs a logged-in graphical desktop. The Python workflow-gate test uses Git's bundled Bash on Windows when available, and skips that shell-dependent test if no working Bash exists. No manual game interaction is needed. Local runs are sequential to limit memory pressure.

Only one harness may run per checkout. Its disposable `run-sable-e2e-*` directories must not contain personal worlds or settings. Fresh worlds and result markers prevent stale passes. Reports live in `build/verification-<mode>.json`, Sable evidence in `build/sable-dimension-stack-e2e/`, and visual evidence in `build/portal-visual-<renderer>-<sable|no-sable>-<mode>-<backend>*/`. CI preserves evidence on failure. After 180 seconds without client log/scene/frame progress, the harness captures a bounded read-only `jcmd Thread.print` from only its descendant or checkout-owned JVMs; it captures again on timeout before process cleanup. Dumps and diagnostic errors live under each result's `thread-diagnostics/`. Each attach has an eight-second limit, at most four JVMs and two MiB per dump. Existing scene-derived client budgets are preserved up to a 105-minute cap, then shrink with elapsed startup/job time to reserve diagnostics, cleanup and artifacts within the existing 120-minute visual workflow budget. Negative-control runs succeed only when the expected named shader/pixel assertion fails, never for an arbitrary boot or compilation failure. Targeted entity/particle negatives remove only the selected drawing path's clipping through an explicitly loaded development-only mixin configuration. They require that exact shader to compile and complete a portal draw without its clipping uniform, then fail only its excluded-region pixel assertion. `clipping-disabled` turns off clip-distance use in the development client to demonstrate that the pixel oracle detects front-plane leakage; it does not alter release code. The separate `--gl-context no-copy-image` Mesa lane requests GL3.3 with ARB_copy_image removed and asserts actual runtime capability absence, without the forced-blit property. Requested Flywheel backends must actually be active; unsupported hardware is a failed/unverified backend lane rather than a silent fallback pass. Run Python regressions alone with `python -m unittest discover -s tools -p "test_*.py"`.

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
