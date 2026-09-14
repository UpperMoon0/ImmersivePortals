"""Fast regression tests for false passes and failed-process supervision."""
import tempfile
import json
import socket
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
        for name in ("server-pass", "client-pass", "client-source", "client-destination", "client-return", "client-recross", "client-dismount"):
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

    def test_windows_session_selection_is_dynamic_and_prefers_current_then_console(self):
        sessions = [(0, 4, ""), (3, 0, "RdpUser"), (7, 0, "ConsoleUser")]
        self.assertEqual(verify.choose_windows_interactive_session(3, 7, sessions), 3)
        self.assertEqual(verify.choose_windows_interactive_session(0, 7, sessions), 7)
        self.assertEqual(verify.choose_windows_interactive_session(0, 99, sessions), 3)

    def test_windows_session_selection_rejects_noninteractive_sessions(self):
        with self.assertRaisesRegex(RuntimeError, "active logged-in Windows desktop session"):
            verify.choose_windows_interactive_session(0, 1, [(0, 4, ""), (1, 4, "ConsoleUser")])

    def test_windows_session_bridge_only_when_sessions_differ(self):
        self.assertFalse(verify.needs_windows_interactive_bridge(7, 7))
        self.assertTrue(verify.needs_windows_interactive_bridge(0, 7))

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

    def test_visual_requires_its_own_authoritative_marker(self):
        with self.assertRaisesRegex(RuntimeError, "visual-pass"):
            verify.validate_results(0, smoke=True)
        (self.results / "visual-pass.txt").write_text("pixels verified")
        verify.validate_results(0, smoke=True)

    def test_live_metrics_reject_missing_samples_nonfinite_and_slow_runs(self):
        good = {"samples": 200, "p95_ms": 12.0, "heap_used_bytes": 1000000}
        for side in ("server", "client"):
            (self.results / f"{side}-metrics.json").write_text(json.dumps(good))
        verify.validate_metrics(200)
        for changes in ({"samples": 199}, {"p95_ms": float("nan")}, {"p95_ms": 101}, {"p95_ms": 0}):
            with self.subTest(changes=changes):
                (self.results / "server-metrics.json").write_text(json.dumps(good | changes))
                with self.assertRaises(RuntimeError):
                    verify.validate_metrics(200)

    def test_e2e_port_is_free_for_tcp_and_udp(self):
        port = verify.choose_tcp_udp_port()
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as tcp, \
             socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            tcp.bind(("127.0.0.1", port))
            udp.bind(("127.0.0.1", port))

    def test_gametest_log_requires_authoritative_required_pass_marker(self):
        log = self.results / "latest.log"
        log.write_text("Failed to start the minecraft server\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "did not report"):
            verify.validate_gametest_log(log)
        log.write_text("All 6 required tests passed :)\n", encoding="utf-8")
        verify.validate_gametest_log(log)

    def test_generated_run_classpath_is_invalidated_without_touching_other_runs(self):
        root = self.results / "checkout"
        wanted = root / ".gradle/configuration/neoForm/x/steps/writeMinecraftClasspathPortalSmokeClient/classpath.txt"
        other = root / ".gradle/configuration/neoForm/x/steps/writeMinecraftClasspathPortalSmokeServer/classpath.txt"
        wanted.parent.mkdir(parents=True)
        other.parent.mkdir(parents=True)
        wanted.write_text("stale", encoding="utf-8")
        other.write_text("keep", encoding="utf-8")
        with patch.object(verify, "ROOT", root):
            verify.invalidate_generated_run_classpath("PortalSmokeClient")
        self.assertFalse(wanted.exists())
        self.assertTrue(other.exists())

    def test_renderer_mod_sources_selects_only_direct_runtime_mods(self):
        cache = self.results / "cache"
        cache.mkdir()
        jars = {
            "sodium-neoforge-0.8.12+mc1.21.1.jar",
            "iris-1.8.12+1.21.1-neoforge.jar",
            "sodium-neoforge-0.8.12+mc1.21.1.jar",
            "veil-neoforge-1.21.1-4.3.2.jar",
            "veil-neoforge-1.21.1-4.3.2-sources.jar",
        }
        for name in jars:
            (cache / name).write_bytes(b"jar")
        classpath = self.results / "classpath.txt"
        classpath.write_text("\n".join(str(cache / name) for name in sorted(jars)), encoding="utf-8")

        self.assertEqual([p.name for p in verify.renderer_mod_sources(classpath, "vanilla")], [])
        self.assertEqual(
            [p.name for p in verify.renderer_mod_sources(classpath, "sodium")],
            ["sodium-neoforge-0.8.12+mc1.21.1.jar"],
        )
        self.assertEqual(
            {p.name for p in verify.renderer_mod_sources(classpath, "iris")},
            {"iris-1.8.12+1.21.1-neoforge.jar", "sodium-neoforge-0.8.12+mc1.21.1.jar"},
        )
        self.assertEqual(
            {p.name for p in verify.renderer_mod_sources(classpath, "veil")},
            {"sodium-neoforge-0.8.12+mc1.21.1.jar", "veil-neoforge-1.21.1-4.3.2.jar"},
        )

    def test_renderer_mod_sources_rejects_missing_or_ambiguous_runtime(self):
        classpath = self.results / "classpath.txt"
        classpath.write_text("", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "expected one sodium"):
            verify.renderer_mod_sources(classpath, "sodium")

    def test_sable_gametest_holders_are_loadable_without_sable(self):
        gametest_dir = verify.ROOT / "src/main/java/qouteall/imm_ptl/core/gametest"
        holders = [
            path for path in gametest_dir.glob("*GameTest.java")
            if '@GameTestHolder("sable")' in path.read_text(encoding="utf-8")
        ]
        self.assertTrue(holders)
        for path in holders:
            source = path.read_text(encoding="utf-8")
            with self.subTest(holder=path.name):
                self.assertNotIn("dev.ryanhcode.", source)

    def test_sable_client_packet_mixin_is_client_only(self):
        config = json.loads((verify.ROOT / "src/main/resources/imm_ptl_compat.mixins.json").read_text(encoding="utf-8"))
        name = "sable.MixinClientboundStartTrackingSubLevelPacket_SablePortalCompat"
        self.assertNotIn(name, config.get("mixins", []))
        self.assertIn(name, config.get("client", []))


if __name__ == "__main__":
    unittest.main()
