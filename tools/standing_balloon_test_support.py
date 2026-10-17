"""Complete standing-flight receipts shared by validator and runner tests."""
import json


STANDING_MARKERS = (
    "server-pass", "client-pass", "balloon-client-ready-0", "balloon-client-ready-1",
    "balloon-client-arrived-0", "balloon-client-arrived-1",
)


def readiness(dimension, keys):
    return dict(dimension=dimension, world_chunks={k: True for k in keys},
                plot_chunks={"32,32": True}, world_ready=True, plot_ready=True)


def write_standing_balloon_evidence(root, cold=False):
    for name in STANDING_MARKERS:
        (root / (name + ".txt")).write_text("observed")
    receipts = []
    for case, standing in enumerate(("envelope", "deck")):
        remote = 16384 + case * 8192 + 8
        keys = [f"{x},{z}" for x in range((remote - 12) >> 4, ((remote + 12) >> 4) + 1)
                for z in range((remote - 12) >> 4, ((remote + 12) >> 4) + 1)] if cold else ["0,0"]
        body = f"00000000-0000-4000-8000-{case + 1:012d}"
        nonce = f"00000000-0000-4000-8000-{case + 20:012d}" if cold else ""
        row = dict(case=case, standing=standing, body=body,
                   player="00000000-0000-4000-8000-000000000099",
                   support=True, force_loaded=False, native_flight_body_writes=0,
                   standing_error=.01, source_gas=300, arrived_gas=400, server_samples=80,
                   blocks=484, arrival_y=-40, observed_y=80,
                   source_body_absent=True, destination_body_present=True,
                   source_readiness=readiness("minecraft:the_nether", keys),
                   destination_readiness=readiness("minecraft:overworld", keys),
                   cold_start=cold, cold_nonce=nonce)
        receipts.append(row)
        client = dict(case=case, body=body,
                      handoff=f"00000000-0000-4000-8000-{case + 10:012d}",
                      handoff_completed=True, failed_handoffs=0,
                      source_body_absent=True, destination_body_present=True,
                      supported=True, samples=40, maximum_error=.01,
                      source_readiness=row["source_readiness"],
                      destination_readiness=row["destination_readiness"],
                      cold_start=cold, cold_nonce=nonce)
        (root / f"balloon-client-case-{case}.json").write_text(json.dumps(client))
        (root / f"balloon-standing-client-{case}.json").write_text(json.dumps(
            dict(supported=True, samples=40, maximum=.01)))
        if cold:
            for side in ("server", "client"):
                (root / f"balloon-cold-{side}-{case}.json").write_text(json.dumps(dict(
                    case=case, nonce=nonce, dimension="minecraft:overworld",
                    remote_x=remote, remote_z=remote, samples=20,
                    world_chunks={k: False for k in keys},
                    outside_loaders=True, prepared_before_absence=True)))
    (root / "balloon-server.json").write_text(json.dumps(receipts))
    return receipts
