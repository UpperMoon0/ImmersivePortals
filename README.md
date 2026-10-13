# Immersive Portals - CE

> **Notice:** **Immersive Portals - CE** is an independently community-maintained distribution of Immersive Portals for NeoForge. It is not the official Immersive Portals release.

Immersive Portals adds see-through portals and seamless travel between dimensions. Portals can be nested, transformed, scaled, and used to build non-Euclidean spaces without loading screens.

This repository maintains the Minecraft 1.21.1 NeoForge build derived from [qouteall's Immersive Portals](https://github.com/iPortalTeam/ImmersivePortalsModForNeo). The original project and its contributors remain the foundation of this distribution.

## Features

- See through portals before entering them.
- Travel between dimensions without a loading screen.
- Render portals inside other portals.
- Build mirrors, wrapping worlds, dimension stacks, and custom portal networks.
- Change player scale through configured scaling portals.
- Use commands, datapacks, and APIs to create custom portal behavior.
- Run alongside Sable 2.0.5 with seamless, verified portal handoff for moving sublevels, riders, gravity, and remote tracking.

## Minecraft and loader support

- Minecraft 1.21.1
- NeoForge for Minecraft 1.21.1; tested baseline 21.1.228
- Java 21
- Cloth Config API 15.0+ for NeoForge (tested: 15.0.140+neoforge)

The Fabric project is maintained separately by the original Immersive Portals team. This repository produces the NeoForge jar only.

## Mod compatibility

These are **Minecraft 1.21.1 / NeoForge** targets for this CE build, not a promise of compatibility with every version or modpack. Optional mods below are not required for ordinary portals. Use the listed versions when reproducing a problem.

### Renderers and shader loaders

Choose **one** terrain renderer. Shader loaders are optional client additions.

| Install | What works with portals | Verified configuration |
| --- | --- | --- |
| Vanilla renderer | See-through portals, nested views, mirrors and dimension travel without an extra renderer mod. | With and without Sable. |
| **Sodium 0.8.12+mc1.21.1** | Optimized terrain rendering keeps destination chunks, transparency and nested portal views visible. | With Sable; shaders off or with the Iris build below. |
| **Official Iris 1.8.14-beta.1+1.21.1-neoforge** + Sodium above | Shaderpack rendering through portals, including clipping geometry at the portal surface, nested views, mirrors and dimension travel. | With Sable, both shaders off and active-pack tests. |
| **Embeddium 1.0.15+mc1.21.1** | Alternative optimized terrain renderer with destination-chunk, transparency and nested-view integration. | Without Sable; shaders off or with NeOculus below. |
| **NeOculus 1.8.7** + Embeddium above | Shaderpack portal rendering on the Embeddium stack, with the same targeted clipping and world-view integration. | Without Sable, both shaders off and active-pack tests. |

Do not combine Sodium with Embeddium, or official Iris with NeOculus. Iris requires Sodium; NeOculus requires Embeddium. **Classic Oculus is not the supported NeOculus build.** Other Embeddium/NeOculus versions are rejected by the compatibility checks. Other Sodium/Iris versions and unlisted combinations are unverified. The tested Sable presence/absence above is coverage, not a requirement to add or remove Sable.

### Shaderpacks

Both of these unmodified packs passed graphical portal tests on **Iris + Sodium** and **NeOculus + Embeddium**:

| Pack | Tested preset | Coverage |
| --- | --- | --- |
| **Complementary Reimagined r5.5.1** | `POTATO` | Portal clipping, nesting, mirrors, dimension crossing and Create rendering. |
| **MakeUp Ultra Fast 9.4a** | `shadowless_high` | The same portal scenes with this exact shadowless preset. |

Tests repeat after resource reload, resize and shader disable/enable. These results do **not** certify other presets, Complementary Unbound, arbitrary shaderpacks or pack-specific shadows. Separate test packs cover clipping and shadow regressions. Install your shaderpack separately; none is bundled. Normal portal rendering supports nesting; the compatibility/debug portal renderers are limited to one layer.

### Gameplay and rendering integrations

| Mod / tested version | What the integration does | Scope and limits |
| --- | --- | --- |
| **Sable 2.0.5** | Moving sublevels cross portals and dimension stacks while keeping their identity, velocity, riders and remote tracking. Rotated crossings preserve the tested sublevel camera/gravity behavior. | Dedicated-server + real-client tests cover UDP and TCP, a Create seat, gravity-driven recrossing and dismount. Verification uses Sable Companion 1.6.0 alongside Sable. This does not certify every Sable add-on or vehicle. |
| **Create 6.0.10-280 / Flywheel 1.0.6** | Create blocks and contraptions remain visible and clipped through portals; rotating machinery and block-breaking overlays are tested. | Portal, alternate-world and clipped views use Create's vanilla rendering fallback. A main view with a portal-colliding entity also falls back for that view; otherwise the selected Flywheel backend is preserved. Off, instancing and indirect have graphical coverage, not a universal hardware/FPS guarantee. |
| **Veil 4.3.2** | Keeps portal clipping working when Veil recompiles Minecraft shaders, so geometry does not leak across the portal plane. | Tested as standalone Veil with Sodium and without Sable; Sable's bundled Veil is also exercised in Sable lanes. This does not certify every mod using Veil. |
| **owo-lib 0.12.15-beta.12+1.21 / Forgified Fabric networking 4.2.2+a92978fd19** | Protects parallel packet registration from races that can break startup or login in mixed-mod installations. | Targeted concurrency tests and the runtime stack cover the pinned networking module bundled by owo-lib. Other versions of that module are rejected; this is not general Fabric-mod support. |

### Limited or unverified support

- **Pehkui 3.8.3+1.21-neoforge** is present in the test stack, but has no dedicated Pehkui interaction acceptance test. This port uses Minecraft's scale attribute for living-entity portal scaling; Pehkui is not required for that feature.
- **Mekanism 10.7.14.79** has targeted Sable QIO-dashboard inventory regression coverage. This is not a blanket claim for every Mekanism machine or network through portals.
- **Aether, Architectury and GeckoLib** are present as development/test dependencies. Loading alongside them does not establish complete gameplay compatibility.
- **Gravity Changer / Gravity API** integration is unfinished in this NeoForge port. Do not expect general player-gravity switching from it; Sable's tested sublevel gravity behavior above is a separate integration.
- **Requiem and Cardinal Components** have optional source hooks, but no verified Minecraft 1.21.1 NeoForge runtime combination here. **Aeronautics, Simulated and Offroad** are local-development inputs without reproducible hosted-CI coverage.
- Mods or versions not listed here are **unverified**, rather than automatically compatible or incompatible. This jar is not a Fabric or legacy Forge build.

For exact artifacts and technical limits, see the [renderer contract](docs/renderer-backends.md), [Create/Flywheel audit](docs/compat/flywheel-1.0.6.md) and [networking audit](docs/compat/fabric-network-registration.md). The [verified integration CI run](https://github.com/UpperMoon0/ImmersivePortals/actions/runs/38136969940) records the tested combinations; it does not cover every possible cross-combination.

## Building and verification

Use Java 21. The canonical verification harness is cross-platform:

```text
python tools/verify.py core   # build + JUnit + NeoForge GameTests
python tools/verify.py e2e    # dedicated server + real graphical client
python tools/verify.py full   # all layers
```

A separate draft complex-vessel suite is available as `python tools/verify.py ships`. It assembles a 49×17×65 multi-chunk Sable ship with cargo, Create machinery and a rider, then exercises ordinary portals and dimension-stack crossings. See [fixture, evidence and limits](docs/compat/sable-complex-ships.md); it is not an Aeronautics propulsion certification.

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
- Graphical PR/main/manual CI: 26 fixture/control lanes cover the nine renderer/Sable combinations, active shaders, shadow positives/negatives, clipping negatives, compatibility/debug copy paths, and Flywheel off/instancing/indirect. Four real-pack lanes test both pinned packs on Iris and NeOculus immediately after integration-check selection, overlapping core, the eight UDP/TCP networking/impairment checks and the fixture/control matrix. Every selected lane remains required.
- Nightly/manual extended workflow: the same 30 graphical configurations with 1200 runtime samples each. Compatibility/debug renderers have a one-layer limit, so nesting is required by normal-renderer lanes only.
- Release: core, Sable E2E and all declared renderer, active-pack, shadow, negative-control, real-pack, framebuffer-blit and Flywheel configurations must pass before publishing the jar produced by core verification. Automated releases reuse successful main CI only for the exact tagged commit: all 41 checks (including every UDP/TCP networking and impairment lane) must have succeeded in the same run attempt, and its preserved jar must match the recorded SHA-256, version and provenance. No tests or sample counts are reduced. Missing, expired or ineligible evidence runs the full release matrix instead; a corrupt or missing artifact after selection fails publication.

The Sable native matrix uses UDP and TCP with bundled networking, TxniLib's 4.3.0 module and the newer 4.3.1 module; the latter also runs with port-scoped latency, jitter and packet loss. Every lane requires detached movement, lifecycle invalidation, post-Ack timeout, 20 repeated handoff cycles and actual disconnect cleanup. See [rider regressions](docs/compat/sable-rider-handoff.md) and [networking compatibility](docs/compat/forgified-fabric-networking.md) for exact versions, platform requirements and test limits.

Configure branch protection to require **Required verification**. This stable aggregate job fails if any selected test failed, was cancelled or unexpectedly skipped. A workflow file alone cannot configure repository branch protection.

After successful push CI on `main`, a version change creates a tag at that verified commit and explicitly dispatches Release with its source CI run ID. Tags pushed with `GITHUB_TOKEN` do not automatically trigger another push workflow. Manual Release dispatch accepts an existing version tag, including for retrying a failed publication; its optional `source_ci_run_id` must pass the same exact-commit/coverage checks. Omit it to verify afresh. Tags pushed by a user run full Release verification directly. The PR and main gates still run independently; prior-commit or PR evidence cannot authorize publication. The coverage contract in `tools/ci-coverage.json` is checked against CI matrix members by Python regression tests.

Graphical jobs print scene request/readiness changes and a heartbeat every minute, including elapsed time, remaining client budget and time since the last observed activity. These messages do not count as client progress or extend deadlines. Per-scene client evidence also records ready/first-check/pass timestamps and frame counts, assertion attempts, and depth-readback count/time. Depth probes run only on assertion frames, retaining all nested views and restoring GL state; each accepted sample must have an exact scene/phase/frame-bound 9×9 witness, with three distinct passing samples at the original ten-frame spacing. The original 120-frame warmup, pixel/depth thresholds, reload/resize/toggle phases, crossing, fault injection and performance sample counts remain unchanged. Real packs start as soon as integration selection completes; the aggregate still fails if core, either transport, any fixture or any real pack fails. Overlapping real packs removes their wait for earlier jobs; eligible releases avoid one entire repeated acceptance matrix. Actual savings depend on hosted-runner scheduling and graphical speed.

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
