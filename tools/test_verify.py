"""Fast regression tests for false passes and failed-process supervision."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import verify


class VerificationHarnessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.results = Path(self.temp.name)
        patcher = patch.object(verify, "RESULT_DIR", self.results)
        patcher.start()
        self.addCleanup(patcher.stop)
        for name in ("server-pass", "client-pass", "client-source", "client-destination", "client-return"):
            (self.results / f"{name}.txt").write_text("verified\n")

    def test_both_sides_and_every_phase_are_required(self):
        verify.validate_results(0)
        for marker in self.results.glob("*.txt"):
            with self.subTest(marker=marker.name):
                marker.unlink()
                with self.assertRaisesRegex(RuntimeError, "missing or empty"):
                    verify.validate_results(0)
                marker.write_text("")
                with self.assertRaisesRegex(RuntimeError, "missing or empty"):
                    verify.validate_results(0)
                marker.write_text("verified\n")

    def test_failure_overrides_pass(self):
        for side in ("server", "client"):
            marker = self.results / f"{side}-fail.txt"
            marker.write_text(f"{side} failed")
            with self.assertRaisesRegex(RuntimeError, f"{side} failed"):
                verify.validate_results(0)
            marker.unlink()

    def test_nonzero_exit_overrides_pass(self):
        with self.assertRaisesRegex(RuntimeError, "status 1"):
            verify.validate_results(1)

    def test_server_death_fails_without_waiting_for_client_timeout(self):
        with self.assertRaisesRegex(RuntimeError, "server exited"):
            verify.wait_for_client(Mock(poll=Mock(return_value=1), returncode=1), Mock())

    def test_live_client_times_out(self):
        with self.assertRaises(TimeoutError):
            verify.wait_for_client(Mock(), Mock(), timeout=0)

    def test_clean_exit_still_requires_results(self):
        (self.results / "client-pass.txt").unlink()
        with self.assertRaisesRegex(RuntimeError, "client-pass"):
            verify.wait_for_client(Mock(poll=Mock(return_value=None)),
                                   Mock(poll=Mock(return_value=0), returncode=0))

    def test_critical_runtime_error_invalidates_pass(self):
        log = self.results / "client.log"
        log.write_text("MixinApplyError: broken transform\n")
        with patch.object(verify, "CLIENT_LOG", log), patch.object(verify, "SERVER_LOG", log):
            with self.assertRaisesRegex(RuntimeError, "MixinApplyError"):
                verify.validate_log_health()

    def test_preparation_removes_stale_results_and_world(self):
        root = self.results
        server = root / "server"
        client = root / "client"
        results = root / "results"
        for directory in (server / "world", server / "logs", client / "logs", results):
            directory.mkdir(parents=True)
            (directory / "stale.txt").write_text("old pass")
        with patch.multiple(verify, ROOT=root, SERVER_DIR=server, CLIENT_DIR=client, RESULT_DIR=results):
            env = verify.prepare_e2e()
        self.assertEqual(list(results.iterdir()), [])
        self.assertFalse((server / "world").exists())
        self.assertIn(f"server-port={env['IP_SABLE_E2E_PORT']}", (server / "server.properties").read_text())

    def test_cleanup_refuses_paths_outside_checkout(self):
        with patch.object(verify, "ROOT", self.results / "checkout"):
            with self.assertRaisesRegex(RuntimeError, "unsafe E2E cleanup"):
                verify.prepare_e2e()
        self.assertTrue((self.results / "client-pass.txt").exists())


if __name__ == "__main__":
    unittest.main()
