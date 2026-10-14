# Sable snapshot dimension provenance

## Defect

Sable 2.0.5 UDP snapshot entries identify a plot coordinate, not its world. Its queued client dispatch selects `Minecraft.level` when the task runs. A server-side dimension change can precede the client's authoritative dimension packet: a destination snapshot can update the still-live source plot. Conversely, an old source datagram can arrive after the client switches and update the destination plot. Snapshot-info can contaminate the wrong world's interpolation clock too. The migration deliberately retains a common plot coordinate, so coordinate lookup alone cannot distinguish these worlds.

A PR #31 initial run recorded a first-frame continuity failure consistent with this ordering gap. Its logs do not identify the individual corrupting datagram. A subsequent passing run does not disprove the source-level race.

## Version-one contract

The optional NeoForge play channel `imm_ptl:sable_snapshot_dimension_v1` advertises support in both directions. No capability packet is sent, no user configuration is changed, and capability decisions use the current connection's negotiated channel table.

- New server and new client: same-server-world movement retains genuine native UDP. Snapshot and snapshot-info encoders append `IPS1` plus the dimension resource location. The corresponding decoder consumes and validates the complete trailer. Client dispatch selects only an already-loaded world by that explicit key, independently of the current player world. Missing/disposed worlds are not recreated. Negotiated native UDP without provenance is discarded.
- New server and older client: use existing dimension-redirected TCP with unchanged legacy snapshot bytes. No trailer is sent to a peer lacking the optional channel.
- New client and older IP server: decline Sable UDP authentication when the IP handoff channel exists but the snapshot capability does not. Sable retains its ordinary TCP fallback. This does not edit the player's configuration. IP-free servers retain their existing behavior.
- Remote portal observers retain the existing redirected-TCP route.
- Integrated-server local transport serializes/decodes through the same format and uses the same addressed dispatch. It does not rely on UDP authentication.

This is an optional additive protocol; no existing IP or Sable channel version changes. Mixed-version compatibility is covered by explicit transport decision/registration contracts and legacy wire equivalence, not a claim that every historical mod combination was booted.

## Regression coverage

The dedicated graphical client creates two temporary client plots at the same local coordinate and a shared body UUID, with distinct poses. A negative control executes the original native API against the wrong world and requires actual wrong-container contamination. It then round-trips the real Sable codecs and invokes the actual queued UDP handler with destination-before-switch and source-after-switch orderings. Snapshot and snapshot-info must affect only the addressed container. Unknown-world, advertised-but-unloaded-world and missing-provenance datagrams must not mutate either world or create a world. The advertised-world test temporarily hides and restores its loaded registry entry without disposing its resources.

A thread-selective forwarding queue isolates synthetic submissions while concurrent network submissions retain the live queue; a background-thread sentinel checks that separation. Temporary plots are removed and both worlds' interpolation clocks are restored in `finally`; the real flight plots are never reused for the probe. Legacy TCP/UDP snapshot bytes are compared against the pinned native format. Core tests reject malformed, truncated, unknown-version and extra trailers, and pin the two native send/dispatch/codec paths.

The eight existing rider/network CI lanes require both `client-snapshot-dimensions` and `client-snapshot-transport` receipts. After excluding synthetic probe deliveries, UDP lanes require actual native addressed movement and TCP lanes require actual ordered movement with no native UDP movement. The existing 32-block continuity threshold, handoff deadlines, impaired networking, renderer checks and all six ship lanes are unchanged. Exact-head runtime results belong in PR #31; adding these tests does not imply they have passed.

## Interpolation-history oracle

The original graphical oracle required three samples after the return handoff. Diagnostic run 38511745783 demonstrated successful grafting of `[1707,1708,1709,1718,1719]`, followed by normal native pruning to `[1718,1719]` when the pointer reached 1718. That post-tick count did not prove a reset.

The replacement checks every eligible transformed source sample (tick and complete pose components) immediately at the graft boundary, before native sampling/pruning, and requires a checked-graft receipt bound to the actual destination instance at return and recross. The two native full-sync samples remain required at the graphical assertion. A deterministic native pruning control reproduces the observed sequence, and a missing-history negative control must fail. Flight continuity, camera checks and deadlines are unchanged.

The terminal cleanup fixture closes and checks the native connection before posting its synthetic cleanup event and tearing down client worlds. This preserves the disconnect assertions while preventing incoming ordinary block updates from decoding against reverted registries; no critical-error filter is disabled. The runner recognizes an explicit teardown-start receipt only after server success and allows up to ten seconds for final cleanup receipts to arrive after the server observes the close. Missing receipts at expiry, failure markers, nonzero exits, ordinary login disconnects and critical log errors still fail.
