import copy
import unittest

from verify_vs_clockwork_evidence import (
    REQUIRED_CASES, REQUIRED_CLOCKWORK, REQUIRED_FLAGS, verify_receipts
)


def receipts():
    mods = {name: {"version": "pinned", "sha256": "a" * 64}
            for name in ("immersive_portals", "valkyrienskies", "clockwork", "create")}
    server_cases, client_cases = [], []
    for size, route in sorted(REQUIRED_CASES):
        case = dict(size=size, route=route, body_id="one-native-body",
                    blocks=4100 if size == "large" else 176,
                    plot_chunks=20 if size == "large" else 2,
                    yaw_degrees=90 if route == "rotated" else 0,
                    crossings=2, native_physics_ticks=20)
        case.update({field: True for field in REQUIRED_FLAGS
                     if not field.startswith("client_") and field != "native_physics_ticks"})
        server_cases.append(case)
        client_cases.append(dict(size=size, route=route, body_id="one-native-body",
                                 client_chunks_complete=True, client_mesh_complete=True,
                                 client_post_handoff_frames=40))
    server = dict(schema=1, run_id="one", commit="abc", dependencies=mods,
                  dedicated_server=True, cases=server_cases,
                  clockwork_mechanics=sorted(REQUIRED_CLOCKWORK))
    client = dict(schema=1, run_id="one", commit="abc", dependencies=copy.deepcopy(mods),
                  graphical_client=True, cases=client_cases,
                  screenshots_sha256={"arrived.png": "b" * 64})
    return server, client


class EvidenceContractTest(unittest.TestCase):
    def test_valid_receipt_contract(self):
        self.assertEqual([], verify_receipts(*receipts()))

    def test_no_server_client_identity_mismatch(self):
        server, client = receipts()
        client["run_id"] = "other"
        self.assertIn("Server/client run identity mismatch", verify_receipts(server, client))

    def test_missing_native_body_cannot_pass(self):
        server, client = receipts()
        server["cases"][0]["same_ship_id"] = False
        self.assertTrue(any("same_ship_id" in err for err in verify_receipts(server, client)))

    def test_missing_large_ship_fails_even_if_control_passes(self):
        server, client = receipts()
        server["cases"] = [r for r in server["cases"] if r["size"] != "large"]
        self.assertTrue(any("Missing scenario set" in err for err in verify_receipts(server, client)))

    def test_missing_mechanics_fails(self):
        server, client = receipts()
        server["clockwork_mechanics"].remove("balloon")
        self.assertTrue(any("Clockwork" in err for err in verify_receipts(server, client)))

    def test_missing_client_rendering_fails(self):
        server, client = receipts()
        client["cases"][0]["client_post_handoff_frames"] = 0
        self.assertTrue(any("frames" in err for err in verify_receipts(server, client)))

    def test_absent_native_physics_fails(self):
        server, client = receipts()
        server["cases"][0]["native_physics_ticks"] = 0
        self.assertTrue(any("physics" in err for err in verify_receipts(server, client)))

    def test_no_empty_or_fake_screenshots(self):
        server, client = receipts()
        client["screenshots_sha256"] = {}
        self.assertTrue(any("screenshot" in err for err in verify_receipts(server, client)))

    def test_mismatched_artifact_versions_fail(self):
        server, client = receipts()
        client["dependencies"]["clockwork"]["sha256"] = "c" * 64
        self.assertTrue(any("Dependency hashes" in err for err in verify_receipts(server, client)))


if __name__ == "__main__":
    unittest.main()
