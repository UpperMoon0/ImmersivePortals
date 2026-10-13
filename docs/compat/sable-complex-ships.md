# Complex assembled Sable ship regression

This development-only suite extends the existing six-block rider E2E; it does not replace it.
Run `python tools/verify.py ships` with Java 21 and a graphical desktop or Xvfb.
Only run the harness in a disposable checkout: its server/client worlds and results are fresh each run.

## Scope

- A 7×5×11 control and a 49×17×65 large ship, genuinely placed in a server world and moved into a Sable sublevel by `SubLevelAssemblyHelper.assembleBlocks`.
- The large fixture must contain at least 3,500 blocks, occupy at least 12 actual plot chunks, and occupy more vertical sections than horizontal chunks. Dimensions are fixed, not inferred from a tiny sparse surrogate.
- Continuous hull/deck, longitudinal spine, ribs, glass canopy, distinct extremity markers, two powered Create motor/shaft/cog drive trains, a real Create seat and player, two loaded barrels and a loaded item drain.
- Ordinary finite portals and dimension-stack boundaries, with 90-degree yaw transforms and four alternating crossings per size/route (16 crossings total).
- Real native rigid-body flight: launch velocity is set once; subsequent native physics ticks carry the assembled vessel through the seam. At least five native flight ticks and eight meters of pre-seam travel are required. Observation barriers use velocity-feedback stationkeeping, never vessel teleportation.
- At each handoff: original UUID only in the destination, retained seat/passenger graph, full block and block-entity manifest, exact cargo inventory, velocity and orientation continuity.
- After each handoff: reverse motor speed, toggle a lever and its normally scheduled lamp, remove/restore a block, and switch a contrasting panel. The real client verifies the complete multi-chunk state, every kinetic speed, unique ownership and passenger relation before acknowledgment. Framebuffer pixels must show the requested panel color; every phase saves a PNG.

Reports are `build/sable-ship-flight-e2e/server-ships.json` and `client-ships.json`. The harness validates the exact case/leg set, matching ship identities, nonvacuous fixture and flight sizes, affirmative invariants, actual screenshot files and clean runtime logs. Dedicated UDP/TCP CI lanes preserve reports, screenshots, configs, logs and crash reports even on failure.

## Important limits

The fixture is an actual assembled Sable vessel with native physics and functional Create machinery. Its launch/hover controller is explicit test input. The motors do not generate aerodynamic lift. Aeronautics, Simulated and Offroad are currently optional local-development jars, not pinned hosted-CI inputs; this suite does **not** certify their balloons, burners, analog controls, fuel consumption or aerodynamic model. The public report about stale burner/lever graphics and a balloon remaining behind at a dimension-stack seam is a motivation, not a reproduced diagnosis.

Panel pixels exercise live block-remeshing; exact lever/lamp/removed-block client states are checked separately. These assertions do not by themselves certify each block's animation or every viewing angle, shader, rendering backend, nested portal, or an unseated walking passenger. Two transport lanes use the pinned baseline runtime, not all networking versions or impairment profiles. Existing renderer and rider-lifecycle suites remain independently required.

## Validation status

This branch is intentionally a draft while actual Java compilation and native graphical CI execute. Python harness regressions run locally; full Gradle startup in the orchestration workspace is blocked by unreachable dependency transport. A harness pass or source inspection is not a runtime compatibility pass. Actual run receipts and any new reproduced product defects will be recorded as the draft progresses.
