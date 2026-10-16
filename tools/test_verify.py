"""Fast regression tests for false passes and failed-process supervision."""
import tempfile
import base64
from contextlib import contextmanager
import json
import math
import socket
import subprocess
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import verify


class VerificationHarnessTest(unittest.TestCase):
    def test_network_stress_filters_only_the_game_port_for_both_transports(self):
        commands = verify.network_stress_commands(25566)
        filters = commands[2:]
        self.assertEqual(len(filters), 4)
        for protocol in ("6", "17"):
            for direction in ("sport", "dport"):
                self.assertTrue(any(["protocol", protocol, "0xff", "match", "ip", direction,
                                     "25566", "0xffff", "flowid", "1:3"] == command[-10:] for command in filters))
        for port in (-1, 0, 65536):
            with self.assertRaises(ValueError):
                verify.network_stress_commands(port)

    def test_network_stress_records_actual_traffic_and_removes_its_policy(self):
        original = "qdisc noqueue 0: root refcnt 2\n"
        stats = "qdisc netem 30: parent 1:3 delay 30ms 10ms loss 0.5%\n Sent 1024 bytes 4 pkt (dropped 1)\n"
        with patch.dict(verify.os.environ, IP_NETWORK_STRESS="true"), patch.object(verify.sys, "platform", "linux"), \
                patch.object(verify.shutil, "which", return_value="/usr/sbin/tc"), \
                patch.object(verify.subprocess, "run") as run, \
                patch.object(verify.subprocess, "check_output", side_effect=[original, stats]):
            with verify.network_stress(25566):
                pass
            self.assertEqual(run.call_args.args[0], ["sudo", "tc", "qdisc", "del", "dev", "lo", "root"])
            self.assertEqual(json.loads((self.results / "network-stress.json").read_text())["port"], 25566)

    def test_network_stress_refuses_existing_policies_and_cleans_up_after_setup_failure(self):
        with patch.dict(verify.os.environ, IP_NETWORK_STRESS="true"), patch.object(verify.sys, "platform", "linux"), \
                patch.object(verify.shutil, "which", return_value="/usr/sbin/tc"), \
                patch.object(verify.subprocess, "run") as run, \
                patch.object(verify.subprocess, "check_output", return_value="qdisc fq_codel 0: root\n"):
            with self.assertRaisesRegex(RuntimeError, "existing loopback"):
                with verify.network_stress(25566):
                    pass
            run.assert_not_called()
        with patch.dict(verify.os.environ, IP_NETWORK_STRESS="true"), patch.object(verify.sys, "platform", "linux"), \
                patch.object(verify.shutil, "which", return_value="/usr/sbin/tc"), \
                patch.object(verify.subprocess, "check_output", return_value="qdisc noqueue 0: root refcnt 2\n"), \
                patch.object(verify.subprocess, "run", side_effect=[None, subprocess.CalledProcessError(1, "tc"), None]) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                with verify.network_stress(25566):
                    pass
            self.assertEqual(run.call_args.args[0], ["sudo", "tc", "qdisc", "del", "dev", "lo", "root"])

    def test_network_stress_cannot_pass_without_impaired_packets(self):
        with patch.dict(verify.os.environ, IP_NETWORK_STRESS="true"), patch.object(verify.sys, "platform", "linux"), \
                patch.object(verify.shutil, "which", return_value="/usr/sbin/tc"), \
                patch.object(verify.subprocess, "run"), \
                patch.object(verify.subprocess, "check_output", side_effect=["qdisc noqueue 0: root refcnt 2\n",
                     "qdisc netem 30: parent 1:3\n Sent 0 bytes 0 pkt\n"]):
            with self.assertRaisesRegex(RuntimeError, "no game packets"):
                with verify.network_stress(25566):
                    pass
    def test_log_reader_consumes_each_disconnect_once_and_preserves_partial_utf8(self):
        log = Path(self.temp.name) / "server.log"
        reader = verify.AppendedLogReader(log)
        self.assertEqual([], reader.read_new_lines())
        log.write_bytes(b"startup\nDev lost connection: first\nDev lost connection: caf\xc3")
        self.assertEqual(["startup", "Dev lost connection: first"], reader.read_new_lines())
        first_offset = reader.offset
        self.assertEqual([], reader.read_new_lines())
        self.assertEqual(first_offset, reader.offset)
        with log.open("ab") as stream:
            stream.write(b"\xa9\r\nDev lost connection: third\n")
        self.assertEqual(["Dev lost connection: caf\u00e9", "Dev lost connection: third"], reader.read_new_lines())
        self.assertEqual(log.stat().st_size, reader.offset)
        self.assertEqual([], reader.read_new_lines())

    def test_log_reader_resets_after_truncation_or_replacement(self):
        log = Path(self.temp.name) / "server.log"
        log.write_bytes(b"old complete line\nold partial line")
        reader = verify.AppendedLogReader(log)
        self.assertEqual(["old complete line"], reader.read_new_lines())
        log.write_bytes(b"new\n")
        self.assertEqual(["new"], reader.read_new_lines())
        log.rename(log.with_suffix(".old"))
        log.write_bytes(b"replacement log\n")
        self.assertEqual(["replacement log"], reader.read_new_lines())

    def test_disconnect_reports_newest_event_in_the_appended_batch(self):
        log = Path(self.temp.name) / "server.log"
        log.write_text("Dev lost connection: older\nDev lost connection: newer\n")
        server, client = Mock(), Mock()
        server.poll.return_value = client.poll.return_value = None
        with patch.object(verify, "SERVER_LOG", log), patch.object(verify, "collect_thread_diagnostics"):
            with self.assertRaisesRegex(RuntimeError, "graphical client disconnected: .*newer"):
                verify.wait_for_client(server, client, smoke=True)

    def test_completed_client_normal_disconnect_passes(self):
        log = Path(self.temp.name) / "server.log"
        log.write_text("Dev lost connection: Disconnected\n")
        server, client = Mock(), Mock()
        server.poll.return_value = None
        client.poll.return_value = client.returncode = 0
        with patch.object(verify, "SERVER_LOG", log), patch.object(verify, "collect_thread_diagnostics") as diagnostics:
            verify.wait_for_client(server, client)
            diagnostics.assert_not_called()

    def test_login_disconnect_fails_with_original_reason_before_timeout(self):
        log = Path(self.temp.name) / "server.log"
        log.write_text("Dev lost connection: Internal Exception: End size 269 is less than fixed size 270\n")
        server, client = Mock(), Mock()
        server.poll.return_value = client.poll.return_value = None
        with patch.object(verify, "SERVER_LOG", log), patch.object(verify, "collect_thread_diagnostics") as diagnostics:
            with self.assertRaisesRegex(RuntimeError, "graphical client disconnected: .*End size 269"):
                verify.wait_for_client(server, client, timeout=6020, smoke=True)
            diagnostics.assert_called_once_with(server, client, "disconnect")

    def test_completed_client_disconnect_still_checks_real_exit_code(self):
        log = Path(self.temp.name) / "server.log"
        log.write_text("Dev lost connection: Disconnected\n")
        server, client = Mock(), Mock()
        server.poll.return_value = None
        client.poll.return_value = 1
        client.returncode = 1
        with patch.object(verify, "SERVER_LOG", log), patch.object(verify, "collect_thread_diagnostics") as diagnostics:
            with self.assertRaisesRegex(RuntimeError, "status 1"):
                verify.wait_for_client(server, client)
            diagnostics.assert_not_called()

    def test_expected_cleanup_waits_for_receipts_then_checks_exit(self):
        (self.results / "client-pass.txt").unlink()
        (self.results / "client-movement-disconnect-started.txt").write_text("started")
        (self.results / "server.log").write_text("Dev lost connection: Disconnected\n")
        server, client = Mock(), Mock()
        server.poll.return_value = None
        client.poll.side_effect = [None, 0]
        client.returncode = 0
        def finish_cleanup(_):
            (self.results / "client-pass.txt").write_text("verified")
        with patch.object(verify.time, "sleep", side_effect=finish_cleanup), \
             patch.object(verify, "collect_thread_diagnostics") as diagnostics:
            verify.wait_for_client(server, client)
            diagnostics.assert_not_called()

    def test_expected_cleanup_still_rejects_nonzero_exit(self):
        (self.results / "client-pass.txt").unlink()
        (self.results / "client-movement-disconnect-started.txt").write_text("started")
        (self.results / "server.log").write_text("Dev lost connection: Disconnected\n")
        server, client = Mock(), Mock()
        server.poll.return_value = None
        client.poll.return_value = client.returncode = 1
        with patch.object(verify, "collect_thread_diagnostics"):
            with self.assertRaisesRegex(RuntimeError, "status 1"):
                verify.wait_for_client(server, client)

    def test_expected_cleanup_still_rejects_late_failure_marker(self):
        (self.results / "client-pass.txt").unlink()
        (self.results / "client-movement-disconnect-started.txt").write_text("started")
        (self.results / "server.log").write_text("Dev lost connection: Disconnected\n")
        server, client = Mock(), Mock()
        server.poll.return_value = client.poll.return_value = None
        def failed_cleanup(_):
            (self.results / "client-fail.txt").write_text("cleanup failed")
        with patch.object(verify.time, "sleep", side_effect=failed_cleanup), \
             patch.object(verify, "collect_thread_diagnostics"):
            with self.assertRaisesRegex(RuntimeError, "cleanup failed"):
                verify.wait_for_client(server, client)

    def test_expected_cleanup_cannot_hide_missing_receipts_forever(self):
        (self.results / "client-pass.txt").unlink()
        (self.results / "client-movement-disconnect-started.txt").write_text("started")
        (self.results / "server.log").write_text("Dev lost connection: Disconnected\n")
        server, client = Mock(), Mock()
        server.poll.return_value = client.poll.return_value = None
        with patch.object(verify.time, "monotonic", side_effect=[0, 0, 11]), \
             patch.object(verify.time, "sleep"), patch.object(verify, "collect_thread_diagnostics") as diagnostics:
            with self.assertRaisesRegex(RuntimeError, "graphical client disconnected"):
                verify.wait_for_client(server, client)
            diagnostics.assert_called_once_with(server, client, "disconnect")

    def test_cleanup_intent_without_server_success_cannot_mask_disconnect(self):
        (self.results / "client-pass.txt").unlink()
        (self.results / "server-pass.txt").unlink()
        (self.results / "client-movement-disconnect-started.txt").write_text("started")
        (self.results / "server.log").write_text("Dev lost connection: denied\n")
        with patch.object(verify, "collect_thread_diagnostics"):
            with self.assertRaisesRegex(RuntimeError, "graphical client disconnected: .*denied"):
                verify.wait_for_client(Mock(), Mock())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.results = Path(self.temp.name)
        patcher = patch.object(verify, "RESULT_DIR", self.results)
        patcher.start()
        self.addCleanup(patcher.stop)
        # Never consume a real previous/current E2E disconnect during an isolated unit test.
        for name, filename in (("SERVER_LOG", "server.log"), ("CLIENT_LOG", "client.log")):
            log_patch = patch.object(verify, name, self.results / filename)
            log_patch.start()
            self.addCleanup(log_patch.stop)
        for name in ("server-pass", "client-pass", "client-source", "client-destination", "client-return", "client-recross", "client-dismount",
                     "client-movement-hold", "client-movement-lifecycle", "client-movement-ack-timeout", "client-movement-soak", "client-movement-disconnect",
                     "client-networking-api", "client-snapshot-dimensions", "client-snapshot-transport"):
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

    def test_scoped_jvm_discovery_excludes_unrelated_java_processes(self):
        processes = {101: {"ppid": 1, "name": "sh"}, 102: {"ppid": 101, "name": "java"},
                     103: {"ppid": 102, "name": "java"}, 104: {"ppid": 101, "name": "Xvfb"},
                     201: {"ppid": 1, "name": "java"}, 202: {"ppid": 1, "name": "java"}}
        paths = {102: verify.ROOT, 103: verify.CLIENT_DIR, 201: Path("/unrelated/project"), 202: verify.SERVER_DIR}
        with patch.object(verify, "process_cwd", side_effect=lambda pid: paths.get(pid)):
            selected = verify.project_java_processes({101}, processes)
        self.assertEqual({process["pid"] for process in selected}, {102, 103, 202})
        self.assertEqual(selected[0]["pid"], 103)
        self.assertEqual(next(process for process in selected if process["pid"] == 202)["scope"], "checkout-cwd")

    def test_thread_diagnostics_are_read_only_bounded_and_preserved(self):
        process = {"pid": 102, "ppid": 101, "name": "java", "scope": "descendant", "game": True}
        jcmd = Path("/fake/jdk/bin/jcmd")
        def dump(command, **kwargs):
            self.assertEqual(command, [str(jcmd), "102", "Thread.print", "-l"])
            self.assertEqual(kwargs["timeout"], verify.DIAGNOSTIC_COMMAND_SECONDS)
            kwargs["stdout"].write(b"A" * (verify.DIAGNOSTIC_MAX_BYTES + 1000))
            return Mock(returncode=0)
        with patch.object(verify, "process_snapshot", return_value={}), \
             patch.object(verify, "project_java_processes", return_value=[process]), \
             patch.object(verify, "jcmd_for_process", return_value=jcmd), \
             patch.object(verify.subprocess, "run", side_effect=dump):
            verify.collect_thread_diagnostics(Mock(pid=101), Mock(pid=100), "no-progress-180s")
        directory = self.results / "thread-diagnostics/no-progress-180s"
        report = json.loads((directory / "diagnostics.json").read_text())
        self.assertTrue(report["jvms"][0]["truncated"])
        self.assertEqual((directory / "jvm-102-threads.txt").stat().st_size, verify.DIAGNOSTIC_MAX_BYTES)

    def test_jcmd_timeout_does_not_hide_primary_client_timeout(self):
        process = {"pid": 102, "ppid": 101, "name": "java", "scope": "descendant", "game": True}
        with patch.object(verify, "process_snapshot", return_value={}), \
             patch.object(verify, "project_java_processes", return_value=[process]), \
             patch.object(verify, "jcmd_for_process", return_value=Path("/fake/jdk/bin/jcmd")), \
             patch.object(verify.subprocess, "run", side_effect=subprocess.TimeoutExpired("jcmd", 8)):
            verify.collect_thread_diagnostics(Mock(pid=101), Mock(pid=100), "timeout")
        report = json.loads((self.results / "thread-diagnostics/timeout/diagnostics.json").read_text())
        self.assertIn("timed out", report["jvms"][0]["error"])

    def test_client_stall_captures_once_and_timeout_captures_before_cleanup(self):
        with patch.object(verify.time, "monotonic", side_effect=[0, 181, 250, 301]), \
             patch.object(verify.time, "sleep"), \
             patch.object(verify, "client_progress_token", return_value=()), \
             patch.object(verify, "collect_thread_diagnostics") as diagnostics:
            with self.assertRaises(TimeoutError):
                verify.wait_for_client(Mock(poll=Mock(return_value=None)), Mock(poll=Mock(return_value=None)), timeout=300)
        self.assertEqual([call.args[2] for call in diagnostics.call_args_list], ["no-progress-180s", "timeout"])

    def test_new_client_progress_resets_stall_clock_without_extending_deadline(self):
        with patch.object(verify.time, "monotonic", side_effect=[0, 170, 340, 501]), \
             patch.object(verify.time, "sleep"), \
             patch.object(verify, "client_progress_token", side_effect=[(), (1,), (2,)]), \
             patch.object(verify, "collect_thread_diagnostics") as diagnostics:
            with self.assertRaises(TimeoutError):
                verify.wait_for_client(Mock(poll=Mock(return_value=None)), Mock(poll=Mock(return_value=None)), timeout=500)
        self.assertEqual([call.args[2] for call in diagnostics.call_args_list], ["timeout"])

    def test_progress_reports_scene_changes_and_bounded_heartbeat(self):
        reporter = verify.ClientProgressReporter(0, 300)
        with patch("builtins.print") as printed:
            reporter.update(0, 0)
            reporter.update(0.25, 0)
            reporter.update(1, 0)
            self.assertEqual(printed.call_count, 1)
            (self.results / "scene-request.txt").write_text("after-reload:nested-background")
            reporter.update(2, 2)
            self.assertIn("after-reload:nested-background", printed.call_args.args[0])
            (self.results / "scene-ready.txt").write_text("after-reload:nested-background")
            reporter.update(3, 3)
            reporter.update(62, 3)
            self.assertEqual(printed.call_count, 3)
            reporter.update(63, 3)
            self.assertIn("last activity=60s ago", printed.call_args.args[0])
            self.assertIn("remaining=237s", printed.call_args.args[0])
            (self.results / "visual-pass.txt").write_text("passed")
            reporter.update(64, 64)
            self.assertIn("collecting timing samples", printed.call_args.args[0])
        self.assertEqual(reporter.deadline, 300)

    def test_progress_marker_read_errors_do_not_fail_the_graphical_session(self):
        reporter = verify.ClientProgressReporter(0, 300)
        with patch.object(Path, "open", side_effect=PermissionError("temporarily unavailable")), \
             patch("builtins.print") as printed:
            reporter.update(0, 0)
        self.assertIn("awaiting client", printed.call_args.args[0])
        self.assertEqual(reporter.deadline, 300)

    def test_visual_budget_accounts_for_elapsed_job_and_cleanup(self):
        with patch.dict(verify.os.environ, {"IP_VERIFY_JOB_START_EPOCH": "700"}), \
             patch.object(verify.time, "time", return_value=2000), patch.object(verify.time, "monotonic", return_value=1000):
            self.assertEqual(verify.visual_run_deadline(), 6780)
        workflow = (verify.ROOT / ".github/workflows/visual.yml").read_text()
        self.assertIn("timeout-minutes: 120", workflow)
        self.assertIn("IP_VERIFY_JOB_START_EPOCH", workflow)

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

    def test_windows_session_bridge_forwards_shadow_driver_selection(self):
        for renderer in ("iris-active", "neoculus-active"):
            for shadow_mode in ("true", "false"):
                with self.subTest(renderer=renderer, shadow_mode=shadow_mode):
                    env = {"IP_PORTAL_SMOKE": "true", "IP_SHADOW_SMOKE": shadow_mode,
                           "IP_SMOKE_RENDERER": renderer}
                    # Exercise wrapper generation without launching a desktop process.
                    with patch.object(verify.ctypes, "windll", Mock(), create=True):
                        verify.launch_windows_interactive(["gradlew.bat", "runPortalSmokeClient"], env, 7)
                    wrapper = (self.results / "client-session.cmd").read_text(encoding="utf-8")
                    self.assertIn(f'set "IP_SHADOW_SMOKE={shadow_mode}"', wrapper)
                    self.assertIn('set "IP_PORTAL_SMOKE=true"', wrapper)
                    self.assertIn(f'set "IP_SMOKE_RENDERER={renderer}"', wrapper)

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
        with patch.object(verify, "ROOT", self.results / "checkout"), \
             patch.object(verify, "SERVER_DIR", self.results / "server"), \
             patch.object(verify, "CLIENT_DIR", self.results / "client"):
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

    def test_ci_transport_matrix_controls_common_and_client_udp_flags(self):
        workflow = (verify.ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        workflow = workflow.split("  graphical-e2e:\n", 1)[1].split("  portal-visual:\n", 1)[0]
        self.assertIn('transport: [udp, tcp]', workflow)
        self.assertIn('run-sable-e2e-client/config/sable-client.toml', workflow)
        self.assertEqual(workflow.count('disable_udp_pipeline = true'), 1)
        self.assertEqual(workflow.count('disable_udp_pipeline = false'), 1)
        self.assertGreaterEqual(workflow.count('attempt_udp_networking = false'), 2)
        self.assertGreaterEqual(workflow.count('attempt_udp_networking = true'), 2)

    def test_gametest_log_requires_authoritative_required_pass_marker(self):
        log = self.results / "latest.log"
        log.write_text("Failed to start the minecraft server\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "did not report"):
            verify.validate_gametest_log(log)
        log.write_text("All 6 required tests passed :)\n", encoding="utf-8")
        verify.validate_gametest_log(log)

    def test_gametest_pass_marker_cannot_hide_async_cleanup_or_shutdown_failure(self):
        log = self.results / "latest.log"
        for failure in ("Failed to list temporary files in /tmp/ip-miniature-registry-test-example",
                        "Exception stopping the server\njava.util.concurrent.CompletionException: java.nio.file.NoSuchFileException"):
            with self.subTest(failure=failure):
                log.write_text("All 7 required tests passed :)\n" + failure + "\n", encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "asynchronous IO or shutdown failure"):
                    verify.validate_gametest_log(log)

    def test_optional_gametest_failure_cannot_pass_core_verification(self):
        log = self.results / "latest.log"
        log.write_text("All 7 required tests passed :)\n3 optional tests failed\n", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "optional test failures"):
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

    def test_shader_fixture_is_explicit_reproducible_and_lane_scoped(self):
        client = self.results / "client"
        with patch.object(verify, "CLIENT_DIR", client):
            off = verify.stage_shader_fixture("iris")
            self.assertFalse(off["enabled"])
            self.assertIn("enableShaders=false", (client / "config/iris.properties").read_text())
            active = verify.stage_shader_fixture("iris-active")
            self.assertTrue(active["enabled"])
            self.assertTrue(active["expected_active"])
            self.assertEqual(off["sha256"], active["sha256"])
            self.assertEqual(len(active["sha256"]), 64)
            self.assertTrue({"gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_water",
                             "gbuffers_entities", "gbuffers_block", "gbuffers_particles"}.issubset(active["programs"]))
            disabled = verify.stage_shader_fixture("neoculus-active", "pack-disabled")
            self.assertFalse(disabled["enabled"])
            self.assertTrue(disabled["expected_active"])
            self.assertIn("enableShaders=false", (client / "config/oculus.properties").read_text())

    def shader_evidence(self, active=True):
        scenes = {"miniature-visible", "solid-visible", "solid-clipped", "nested", "create-nested", "mirror", "create-visible", "create-clipped", "create-crumbling-clean", "create-crumbling-damaged", "create-crumbling-restored", "create-crumbling-clipped"}
        phases = ["before-reload", "after-reload"]
        if active:
            phases.append("after-toggle")
            scenes.update(f"{program}-{side}" for program in ("cutout", "translucent", "entity", "block-entity", "particle")
                          for side in ("visible", "clipped"))
        checks = [{"phase": phase, "scene": scene, "screenshot": "test.png",
                   "shaders_active": active, "pack": verify.FIXTURE_NAME if active else "",
                   "crossing_motion": {"source": {"player_uuid": "test-player", "dimension": "minecraft:overworld", "player_dimension": "minecraft:overworld", "forward_input": True},
                       "first_destination": {"player_uuid": "test-player", "dimension": "minecraft:the_nether", "player_dimension": "minecraft:the_nether", "eye": [0, 82, -0.1]},
                       "capture": {"player_uuid": "test-player", "dimension": "minecraft:the_nether", "player_dimension": "minecraft:the_nether",
                           "eye": [0, 82, -0.1], "camera": [0, 82, -0.1], "velocity": [0, 0, 0], "forward_input": False,
                           "wall_chunk_loaded": True, "wall_block": "minecraft:lime_concrete",
                           "server": {"current": {"player_uuid": "test-player", "dimension": "minecraft:the_nether", "eye": [0, 82, -0.1]}}},
                       "velocity_stops": 120},
                   "width": 854 if phase == "before-reload" else 960, "height": 480 if phase == "before-reload" else 640,
                   "flywheel": {"nestedContextsRestored": 0 if active else 1, "contextAccessorsInstalled": True, "contextClearedAfterFrame": True,
                                "viewContextWitness": {"observedViews": 4, "openViews": 0, "sameRendererViewsVerified": 1,
                                    "nullContextsVerified": 1, "nonNullContextsVerified": 0, "fallbackScopesVerified": 1}},
                   "nested_context_verification": "deferred-null-view" if active else "overlapping-live-context",
                   "nested_view_contexts_this_scene": {"sameRendererViewsVerified": 1, "nullContextsVerified": 1,
                                                      "nonNullContextsVerified": 0, "fallbackScopesVerified": 1},
                   "source_motion_changed_pixels": 100, "create_motion_changed_pixels": 100,
                   "create_server_motion": True, "nested_contexts_restored_this_scene": 0 if active else 1, "crumbling_changed_pixels": 100, "crumbling_darkening": 10,
                   "crumbling_oracle": {"stage": 9 if scene in ("create-crumbling-damaged", "create-crumbling-clipped") else -1,
                       "client_stage": 9 if scene in ("create-crumbling-damaged", "create-crumbling-clipped") else -1,
                       "position": [0, 82, 3 if scene.endswith("-clipped") else -1], "block": "create:large_cogwheel",
                       "packet_sent": True, "face_samples": 1000, "background_samples": 1000, "fixture_generation": 1,
                       "fixture_unchanged": True, "camera_unchanged": True,
                       "measurement": {"samples": 1000, "darkenedPixels": 200 if scene == "create-crumbling-damaged" else 0,
                           "brightenedPixels": 0, "unchangedPixels": 800, "meanDarkening": 2 if scene == "create-crumbling-damaged" else 0,
                           "darkeningEnergy": 2000 if scene == "create-crumbling-damaged" else 0, "brighteningEnergy": 0,
                           "meanAbsoluteChange": 2 if scene == "create-crumbling-damaged" else 0,
                           "changedFraction": 0.2 if scene == "create-crumbling-damaged" else 0, "backgroundDrift": 0, "backgroundResidual": 0}},
                   "shader_path": {"sourceByDrawName": {"entities_cutout_diffuse": "gbuffers_entities", "particles": "gbuffers_particles"},
                                   "selectedProgramCounts": {"VANILLA:entities_cutout_diffuse": 1, "VANILLA:particles": 1},
                                   "completedDrawCounts": {"entities_cutout_diffuse": {"portalWithUniform": 1}, "particles": {"portalWithUniform": 1}}},
                   "straddling_witness": {"cpu_origin_retained": True, "cpu": {"centers": [[0, 82, -0.75]]},
                       "excluded": {"green_fraction": 1.0 if scene.endswith("-clipped") else 0.0,
                                    "red_fraction": 0.0 if scene.endswith("-clipped") else 1.0},
                       "retained": {"red_fraction": 1.0}, "active_particles": 16},
                   "pipeline": "net.irisshaders.iris.pipeline.IrisRenderingPipeline" if active else "VanillaRenderingPipeline",
                   "renderer": "IrisPortalRenderer" if active else "RendererUsingStencil"}
                  for phase, scene in [(p, s) for p in phases for s in scenes] + [("after-crossing", "crossing")]]
        (self.results / "test.png").write_bytes(b"image")
        (self.results / "crossing-server-pass.txt").write_text("crossed")
        return {"checks": checks, "toggle_disabled_verified": active,
                "framebuffer_copy": {"passed": True, "cases": [
                    {"width": size, "forcedBlit": forced, "path": "framebuffer-blit", "stateRestored": True,
                     "depth": 0.375, "stencil": 77, "color": [0.25, 0.75, 0.5, 1.0]}
                    for size in (8, 13) for forced in (False, True)]}}

    def write_shader_evidence(self, report):
        (self.results / "runtime-evidence.json").write_text(json.dumps(report))

    def test_active_lane_requires_pack_pipeline_and_ce_shader_renderer(self):
        report = self.shader_evidence()
        self.write_shader_evidence(report)
        verify.validate_shader_evidence("iris-active")
        for key, value in (("shaders_active", False), ("pack", "other-pack"),
                           ("pipeline", "VanillaRenderingPipeline"), ("renderer", "RendererUsingStencil")):
            with self.subTest(key=key):
                changed = self.shader_evidence()
                changed["checks"][0][key] = value
                self.write_shader_evidence(changed)
                with self.assertRaises(RuntimeError):
                    verify.validate_shader_evidence("iris-active")

    def test_active_lane_requires_every_scene_reload_toggle_and_crossing(self):
        report = self.shader_evidence()
        for changed in (dict(report, toggle_disabled_verified=False),
                        dict(report, checks=report["checks"][1:]),
                        dict(report, checks=[c for c in report["checks"] if c["phase"] != "after-toggle"]),
                        dict(report, checks=[c for c in report["checks"] if c["scene"] != "crossing"])):
            self.write_shader_evidence(changed)
            with self.assertRaises(RuntimeError):
                verify.validate_shader_evidence("iris-active")

    def test_shader_evidence_requires_actual_screenshot_files(self):
        self.write_shader_evidence(self.shader_evidence())
        (self.results / "test.png").unlink()
        with self.assertRaisesRegex(RuntimeError, "missing screenshot"):
            verify.validate_shader_evidence("iris-active")

    def test_installed_iris_lane_requires_shaders_disabled(self):
        self.write_shader_evidence(self.shader_evidence(active=False))
        verify.validate_shader_evidence("iris")
        self.write_shader_evidence(self.shader_evidence(active=True))
        with self.assertRaisesRegex(RuntimeError, "shaders-off"):
            verify.validate_shader_evidence("iris")

    def test_neoculus_staging_never_selects_sodium_or_official_iris(self):
        names = ["embeddium-1.0.15+mc1.21.1.jar", "neoculus-mc1.21.1-1.8.7.jar",
                 "sodium-neoforge-0.8.12+mc1.21.1.jar", "iris-1.8.14-beta.1+1.21.1-neoforge.jar"]
        for name in names:
            (self.results / name).write_bytes(b"jar")
        classpath = self.results / "classpath.txt"
        classpath.write_text("\n".join(str(self.results / name) for name in names))
        self.assertEqual({p.name for p in verify.renderer_mod_sources(classpath, "neoculus-active")}, set(names[:2]))
        self.assertEqual({p.name for p in verify.renderer_mod_sources(classpath, "iris-active")}, set(names[2:]))

    def test_ci_and_release_require_active_shader_and_negative_control_lanes(self):
        for name in ("ci", "nightly", "release"):
            workflow = (verify.ROOT / f".github/workflows/{name}.yml").read_text()
            for renderer in ("iris", "iris-active", "embeddium", "neoculus-active"):
                self.assertIn(f"renderer: {renderer},", workflow)
            self.assertIn("negative_control: pack-disabled", workflow)
            self.assertIn("negative_control: clipping-disabled", workflow)
            self.assertIn("negative_control: entity-clipping-disabled", workflow)
            self.assertIn("negative_control: particle-clipping-disabled", workflow)
            for renderer in ("iris-active", "neoculus-active"):
                for real_pack in ("makeup", "complementary"):
                    self.assertRegex(workflow, rf"renderer: {renderer}, sable: (true|false), real_pack: {real_pack}")
        for name in ("ci", "nightly", "release"):
            workflow = (verify.ROOT / f".github/workflows/{name}.yml").read_text()
            self.assertIn("disable_copy_image: true", workflow)
            self.assertIn("gl_context: no-copy-image", workflow)
            for backend in ("off", "instancing", "indirect"):
                self.assertIn(f"flywheel_backend: {backend}", workflow)

    def test_release_jar_check_rejects_development_classes(self):
        import zipfile
        root = self.results / "checkout"
        (root / "build/libs").mkdir(parents=True)
        (root / "gradle.properties").write_text("mod_version=1.0\n")
        jar = root / "build/libs/immersive_portals-1.0.jar"
        with patch.object(verify, "ROOT", root):
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("normal.class", b"class")
            verify.validate_release_jar()
            for entry in (
                "qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeClient.class",
                "qouteall/imm_ptl/core/gametest/SableCollisionIntegrationGameTest.class",
                "qouteall/imm_ptl/core/gametest/NestedPortalLoadingGameTest.class",
                "imm_ptl_gametest.mixins.json",
                "imm_ptl_portal_clipping_test.mixins.json",
            ):
                with self.subTest(entry=entry):
                    with zipfile.ZipFile(jar, "w") as archive:
                        archive.writestr("normal.class", b"class")
                        archive.writestr(entry, b"class")
                    with self.assertRaisesRegex(RuntimeError, "development test classes leaked"):
                        verify.validate_release_jar()

    def test_capability_absent_lane_requires_actual_gl33_without_override(self):
        report = self.shader_evidence()
        for check in report["checks"]:
            check.update(gl_version="3.3 Core Mesa", copy_image_available=False, forced_framebuffer_blit=False)
        self.write_shader_evidence(report)
        verify.validate_shader_evidence("iris-active", gl_context="no-copy-image")
        for key, value in (("gl_version", "4.5 Core Mesa"), ("copy_image_available", True), ("forced_framebuffer_blit", True)):
            old = report["checks"][0][key]
            report["checks"][0][key] = value
            self.write_shader_evidence(report)
            with self.assertRaises(RuntimeError):
                verify.validate_shader_evidence("iris-active", gl_context="no-copy-image")
            report["checks"][0][key] = old

    def test_targeted_shader_control_requires_completed_draw_of_the_selected_path(self):
        good = {"sourceByDrawName": {"entities_cutout_diffuse": "gbuffers_entities"},
                "selectedProgramCounts": {"VANILLA:entities_cutout_diffuse": 1},
                "bypassedProgramCounts": {"VANILLA:entities_cutout_diffuse": 1},
                "completedDrawCounts": {"entities_cutout_diffuse": {"portalWithoutUniform": 1}}}
        verify.validate_target_shader_path(good, "entity", False)
        with self.assertRaises(RuntimeError):
            verify.validate_target_shader_path(good, "particle", False)
        with self.assertRaises(RuntimeError):
            verify.validate_target_shader_path(good, "entity", True)
        for field in ("sourceByDrawName", "selectedProgramCounts", "bypassedProgramCounts", "completedDrawCounts"):
            with self.subTest(field=field):
                with self.assertRaises(RuntimeError):
                    verify.validate_target_shader_path(good | {field: {}}, "entity", False)

    def test_entity_and_particle_checks_cannot_pass_from_cpu_culling(self):
        for scene in ("entity-clipped", "particle-clipped"):
            for field in ("cpu", "retained", "excluded"):
                report = self.shader_evidence()
                witness = next(check for check in report["checks"] if check["scene"] == scene)["straddling_witness"]
                witness[field] = {}
                self.write_shader_evidence(report)
                with self.assertRaises(RuntimeError):
                    verify.validate_shader_evidence("iris-active")

    def real_pack_evidence(self):
        report = self.shader_evidence()
        template = report["checks"][0]
        original_checks = {check["scene"]: check for check in report["checks"]}
        checks = []
        for phase in ("before-reload", "after-reload", "after-toggle"):
            for scene in verify.visual_scene_names("iris-active", "normal", False):
                check = template | {"phase": phase, "scene": scene, "width": 854 if phase == "before-reload" else 960,
                                    "height": 480 if phase == "before-reload" else 640}
                reference = scene if scene.endswith("-background") else (
                    "solid-background" if scene.startswith("solid-") else "nested-background" if scene.startswith("nested")
                    else "mirror-background" if scene.startswith("mirror") else "create-nested-background" if scene == "create-nested"
                    else "create-background")
                matching = scene.endswith("-clipped") or scene in ("nested", "mirror")
                targets = {"solid-background": ("minecraft:the_nether:1", 7), "create-background": ("minecraft:the_nether:1", 7),
                           "nested-background": ("minecraft:the_end:2", 8), "create-nested-background": ("minecraft:the_nether:2", 8),
                           "mirror-background": ("minecraft:overworld:1", 10)}
                target, backdrop = targets[reference]
                expectation = "same" if scene.endswith(("-background", "-clipped")) or scene in ("nested", "mirror", "mirror-visible") else "moving-nearer" if scene.startswith("create-") else "nearer"
                witness = {"reference_scene": reference, "reference_screenshot": "test.png",
                           "is_reference": scene.endswith("-background"), "stability_mean_error": 0,
                           "mean_brightness": 20, "actual_brightness": 20, "expected_match": matching,
                           "mean_absolute_error": 0 if matching else 10, "changed_fraction": 0 if matching else 0.5,
                           "depth_samples": 81, "depth_observations": 120, "depth_target": target, "depth_expectation": expectation,
                           "view_distance": backdrop if expectation == "same" else backdrop - 3,
                           "closest_view_distance": backdrop if expectation == "same" else backdrop - 3,
                           "background_view_distance": backdrop}
                check["reference_witness"] = witness
                if scene.startswith("create-crumbling-"):
                    check["crumbling_oracle"] = original_checks[scene]["crumbling_oracle"]
                checks.append(check)
        checks.append(template | {"phase": "after-crossing", "scene": "crossing", "reference_witness": {
            "crossing_palette": True, "background_color": [37, 17, 4], "visible_color": [51, 5, 4], "actual_color": [60, 160, 20],
            "native_view_distance": 2.5, "expected_native_distance": 2.5, "native_camera": [0, 82, -0.5],
            "depth_samples": 81, "depth_observations": 120}})
        for index, check in enumerate(checks):
            frame = 140 * (index + 1)
            observation = f"{check['phase']}:{check['scene']}"
            witness = check["reference_witness"]
            witness.update(depth_frame=frame, depth_observation=observation)
            target = witness.get("depth_target", "minecraft:the_nether:0")
            check["render_frame"] = frame
            check["consecutive_depth_frames"] = [frame] if check["scene"] == "crossing" else [frame - 20, frame - 10, frame]
            check["shader_path"] = dict(check.get("shader_path", {}), frame=frame, observation=observation,
                innerWorldDepthStates={target: dict(frame=frame, observation=observation, sampleCount=81,
                    depthSamples=[0.5] * 81, observationCount=witness["depth_observations"])})
        report["checks"] = checks
        (self.results / "fixture.json").write_text(json.dumps({"name": verify.FIXTURE_NAME, "diagnostic_fixture": False}))
        (self.results / "chunk-recovery-evidence.json").write_text(json.dumps(dict(
            scene="after-reload:nested-background", dimension="minecraft:the_end", chunk=[0, -1],
            injected_failures=2, client_unload_sent=True, ticking_recovered=True,
            entity_ticking_recovered=True, pending_chunk_resent=True)))
        return report

    def test_real_pack_depth_rejects_stale_scene_frame_and_incomplete_convergence(self):
        for scene in ("solid-background", "nested-background", "create-nested", "crossing"):
            for mutation in ("old-frame", "old-scene", "missing-depth", "old-witness", "too-few", "same-frame"):
                with self.subTest(scene=scene, mutation=mutation):
                    report = self.real_pack_evidence()
                    check = next(item for item in report["checks"] if item["scene"] == scene)
                    states = check["shader_path"]["innerWorldDepthStates"]
                    state = next(iter(states.values()))
                    if mutation == "old-frame": state["frame"] -= 10
                    elif mutation == "old-scene": state["observation"] = "old:solid-background"
                    elif mutation == "missing-depth": states.clear()
                    elif mutation == "old-witness": check["reference_witness"]["depth_frame"] -= 10
                    elif mutation == "too-few": check["consecutive_depth_frames"] = []
                    else: check["consecutive_depth_frames"] = [check["render_frame"]] * 3
                    self.write_shader_evidence(report)
                    with self.assertRaisesRegex(RuntimeError, "current scene and assertion frame"):
                        verify.validate_shader_evidence("iris-active")

    def test_real_pack_rejects_missing_or_incomplete_failed_chunk_recovery(self):
        self.real_pack_evidence()
        path = self.results / "chunk-recovery-evidence.json"
        original = json.loads(path.read_text())
        for key in original:
            bad = dict(original)
            del bad[key]
            path.write_text(json.dumps(bad))
            with self.assertRaisesRegex(RuntimeError, "recovery"):
                verify.validate_chunk_recovery_evidence()
        for key in ("client_unload_sent", "ticking_recovered", "entity_ticking_recovered", "pending_chunk_resent"):
            for value in (False, 1, "true"):
                bad = dict(original, **{key: value})
                path.write_text(json.dumps(bad))
                with self.assertRaisesRegex(RuntimeError, "recovery"):
                    verify.validate_chunk_recovery_evidence()
        path.unlink()
        with self.assertRaises(FileNotFoundError):
            verify.validate_chunk_recovery_evidence()

    def test_real_pack_requires_matching_clipped_pixels_and_nearer_visible_depth(self):
        report = self.real_pack_evidence()
        self.write_shader_evidence(report)
        verify.validate_shader_evidence("iris-active")
        cases = (("solid-clipped", "mean_absolute_error", 10), ("solid-clipped", "changed_fraction", 0.5),
                 ("solid-clipped", "view_distance", 2), ("solid-visible", "view_distance", 7),
                 ("solid-visible", "mean_absolute_error", 0), ("solid-background", "mean_brightness", 0),
                 ("mirror", "reference_scene", "solid-background"), ("mirror-background", "view_distance", 1000),
                 ("nested-background", "depth_target", "minecraft:the_nether:1"), ("solid-clipped", "actual_brightness", 0),
                 ("create-background", "depth_observations", 0))
        for scene, key, value in cases:
            with self.subTest(scene=scene, key=key):
                bad = self.real_pack_evidence()
                next(check for check in bad["checks"] if check["scene"] == scene)["reference_witness"][key] = value
                self.write_shader_evidence(bad)
                with self.assertRaises(RuntimeError):
                    verify.validate_shader_evidence("iris-active")

    def test_real_pack_crossing_rejects_red_source_or_blue_sky_palettes(self):
        for color in ([51, 5, 4], [100, 150, 240], [0, 0, 0]):
            report = self.real_pack_evidence()
            report["checks"][-1]["reference_witness"]["actual_color"] = color
            self.write_shader_evidence(report)
            with self.assertRaisesRegex(RuntimeError, "crossing pixels"):
                verify.validate_shader_evidence("iris-active")

    def test_localized_real_pack_geometry_uses_pixel_contrast_and_depth_not_diluted_global_error(self):
        report = self.real_pack_evidence()
        check = next(check for check in report["checks"] if check["scene"] == "create-nested")
        check["reference_witness"].update(mean_absolute_error=1.116, changed_fraction=0.131)
        self.write_shader_evidence(report)
        verify.validate_shader_evidence("iris-active")
        for key, value in (("changed_fraction", 0.09), ("mean_absolute_error", 0),
                           ("actual_brightness", 0), ("closest_view_distance", 8)):
            with self.subTest(key=key):
                bad = json.loads(json.dumps(report))
                next(check for check in bad["checks"] if check["scene"] == "create-nested")["reference_witness"][key] = value
                self.write_shader_evidence(bad)
                with self.assertRaises(RuntimeError):
                    verify.validate_shader_evidence("iris-active")

    def test_real_pack_reference_scenes_are_added_without_changing_fixture_scope(self):
        fixture = verify.visual_scene_names("iris-active", "normal", True)
        real = verify.visual_scene_names("iris-active", "normal", False)
        self.assertFalse(any(scene.endswith("-background") for scene in fixture))
        self.assertTrue({"solid-background", "nested-background", "mirror-background", "create-background", "create-nested-background"}.issubset(real))
        self.assertIn("entity-clipped", fixture)
        self.assertNotIn("entity-clipped", real)
        self.assertNotIn("nested-background", verify.visual_scene_names("iris-active", "compatibility", False))

    def test_pinned_makeup_profile_keeps_its_required_bloom_declaration(self):
        manifest = json.loads((verify.ROOT / "tools/shaderpacks/real-packs.json").read_text())
        makeup = manifest["makeup"]
        self.assertEqual(makeup["profile"], "shadowless_high")
        self.assertEqual(verify.pinned_shaderpack_profile(makeup["sha256"]), "shadowless_high")
        self.assertIsNone(verify.pinned_shaderpack_profile("0" * 64))

    def test_crossing_rejects_coasting_missing_native_chunks_and_changed_player(self):
        good = next(check for check in self.shader_evidence()["checks"] if check["scene"] == "crossing")
        verify.validate_crossing_motion(good)
        for key, value in (("eye", [0, 82, -4]), ("camera", [0, 82, -4]), ("velocity", [0, 0, -0.3]),
                           ("wall_chunk_loaded", False), ("wall_block", "minecraft:air"), ("player_uuid", "replacement")):
            bad = json.loads(json.dumps(good))
            bad["crossing_motion"]["capture"][key] = value
            with self.assertRaises(RuntimeError):
                verify.validate_crossing_motion(bad)

    def test_crossing_stopper_never_teleports_or_repositions_player(self):
        path = verify.ROOT / "src/main/java/qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeClient.java"
        source = path.read_text()
        stopper = source.split("private static void stopDestinationMotion(", 1)[1].split("private static Map<String, Object> crossingPose", 1)[0]
        self.assertIn("setDeltaMovement(Vec3.ZERO)", stopper)
        self.assertNotIn(".setPos(", stopper)
        self.assertNotIn(".teleportTo(", stopper)
        self.assertIn("mc.player.level().dimension().equals(Level.NETHER)", stopper)

    def test_crumbling_requires_packet_stage_static_fixture_and_directional_cracks(self):
        good = self.shader_evidence()
        clean = next(check for check in good["checks"] if check["scene"] == "create-crumbling-clean")["crumbling_oracle"]
        damaged = next(check for check in good["checks"] if check["scene"] == "create-crumbling-damaged")
        verify.validate_crumbling_evidence(damaged, clean)
        for key, value in (("client_stage", -1), ("fixture_generation", 2), ("fixture_unchanged", False), ("camera_unchanged", False)):
            bad = json.loads(json.dumps(damaged))
            bad["crumbling_oracle"][key] = value
            with self.assertRaises(RuntimeError):
                verify.validate_crumbling_evidence(bad, clean)
        for key, value in (("meanDarkening", 0.3), ("brighteningEnergy", 2000), ("darkenedPixels", 0),
                           ("backgroundDrift", 10), ("backgroundResidual", 10)):
            bad = json.loads(json.dumps(damaged))
            bad["crumbling_oracle"]["measurement"][key] = value
            with self.assertRaises(RuntimeError):
                verify.validate_crumbling_evidence(bad, clean)
        restored = next(check for check in good["checks"] if check["scene"] == "create-crumbling-restored")
        verify.validate_crumbling_evidence(restored, clean)
        restored["crumbling_oracle"]["measurement"]["changedFraction"] = 0.2
        with self.assertRaisesRegex(RuntimeError, "clean image"):
            verify.validate_crumbling_evidence(restored, clean)

    def test_pack_profiles_use_declared_values_and_reject_unknown_or_cyclic_presets(self):
        properties = "profile.low = !SHADOWS QUALITY=0 DEPTH:0.5\nprofile.high = profile.low SHADOWS QUALITY=2\n"
        self.assertEqual(verify.shader_profile_options(properties), ("low", {"SHADOWS": "false", "QUALITY": "0", "DEPTH": "0.5"}))
        self.assertEqual(verify.shader_profile_options(properties, "high")[1]["QUALITY"], "2")
        with self.assertRaisesRegex(RuntimeError, "no profile"):
            verify.shader_profile_options(properties, "missing")
        with self.assertRaisesRegex(RuntimeError, "inheritance"):
            verify.shader_profile_options("profile.low = profile.low", "low")
        with self.assertRaisesRegex(RuntimeError, "program enablement"):
            verify.shader_profile_options("profile.low = !program.shadow", "low")

    def test_visual_timeout_scales_with_actual_scene_and_epoch_scope(self):
        base = verify.visual_timeout("sodium", 200, "normal", True)
        active = verify.visual_timeout("iris-active", 200, "normal", True)
        real_pack = verify.visual_timeout("iris-active", 200, "normal", False)
        self.assertGreaterEqual(active, base)
        self.assertGreaterEqual(active, real_pack)
        self.assertGreaterEqual(verify.visual_timeout("iris-active", 1200, "normal", True), active)
        self.assertLessEqual(active, 105 * 60)
        self.assertLessEqual(verify.visual_timeout("iris-active", 12000, "normal", True), 105 * 60)

    def test_live_shader_options_must_match_recorded_preset(self):
        (self.results / "fixture.json").write_text(json.dumps({"name": verify.FIXTURE_NAME, "options": {"SHADOWS": "false"}}))
        self.write_shader_evidence(self.shader_evidence())
        with self.assertRaisesRegex(RuntimeError, "live shaderpack options"):
            verify.validate_shader_evidence("iris-active")

    def test_create_evidence_requires_separate_source_target_motion_and_crumbling(self):
        for scene, key, value in (("create-visible", "source_motion_changed_pixels", 0),
                                  ("create-visible", "create_motion_changed_pixels", 0),
                                  ("create-visible", "create_server_motion", False),
                                  ("create-nested", "nested_context_verification", "overlapping-live-context")):
            with self.subTest(scene=scene, key=key):
                report = self.shader_evidence()
                next(check for check in report["checks"] if check["scene"] == scene)[key] = value
                self.write_shader_evidence(report)
                with self.assertRaises(RuntimeError):
                    verify.validate_shader_evidence("iris-active")

    def test_deferred_flywheel_null_context_requires_fresh_same_renderer_and_scope_evidence(self):
        for target, key, value in (("scene", "sameRendererViewsVerified", 0),
                                    ("scene", "nullContextsVerified", 0),
                                    ("scene", "nonNullContextsVerified", 1),
                                    ("scene", "fallbackScopesVerified", 0),
                                    ("total", "observedViews", 0),
                                    ("total", "openViews", 1),
                                    ("total", "sameRendererViewsVerified", 0)):
            with self.subTest(target=target, key=key):
                report = self.shader_evidence()
                check = next(check for check in report["checks"] if check["scene"] == "create-nested")
                evidence = check["nested_view_contexts_this_scene"] if target == "scene" else check["flywheel"]["viewContextWitness"]
                evidence[key] = value
                self.write_shader_evidence(report)
                with self.assertRaisesRegex(RuntimeError, "deferred same-renderer"):
                    verify.validate_shader_evidence("neoculus-active")

    def test_backend_off_does_not_bypass_deferred_flywheel_context_witness(self):
        report = self.shader_evidence()
        check = next(check for check in report["checks"] if check["scene"] == "create-nested")
        check["flywheel"].update({"actual": "flywheel:off", "backendOn": False})
        self.write_shader_evidence(report)
        verify.validate_shader_evidence("neoculus-active")
        check.pop("nested_view_contexts_this_scene")
        self.write_shader_evidence(report)
        with self.assertRaisesRegex(RuntimeError, "deferred same-renderer"):
            verify.validate_shader_evidence("neoculus-active")

    def test_stencil_flywheel_still_requires_non_null_context_restoration(self):
        report = self.shader_evidence(active=False)
        check = next(check for check in report["checks"] if check["scene"] == "create-nested")
        check["nested_contexts_restored_this_scene"] = 0
        self.write_shader_evidence(report)
        with self.assertRaisesRegex(RuntimeError, "same-dimension nested"):
            verify.validate_shader_evidence("sodium")

    def test_framebuffer_evidence_requires_both_paths_and_actual_values(self):
        for key, value in (("color", [0, 0, 0, 0]), ("depth", float("nan")),
                           ("stencil", 0), ("stateRestored", False), ("path", "copy-image")):
            with self.subTest(key=key):
                report = self.shader_evidence()
                report["framebuffer_copy"]["cases"][1][key] = value
                self.write_shader_evidence(report)
                with self.assertRaises(RuntimeError):
                    verify.validate_shader_evidence("iris-active")
        report = self.shader_evidence()
        report["framebuffer_copy"]["cases"].pop()
        self.write_shader_evidence(report)
        with self.assertRaisesRegex(RuntimeError, "missing live framebuffer"):
            verify.validate_shader_evidence("iris-active")

    def test_framebuffer_resize_evidence_is_mandatory(self):
        report = self.shader_evidence()
        for check in report["checks"]:
            check["width"], check["height"] = 854, 480
        self.write_shader_evidence(report)
        with self.assertRaisesRegex(RuntimeError, "resize was not observed"):
            verify.validate_shader_evidence("iris-active")

    def test_real_pack_staging_records_its_bytes_without_fixture_program_claims(self):
        import hashlib
        import zipfile
        pack = self.results / "RealPack-1.2.zip"
        with zipfile.ZipFile(pack, "w") as archive:
            archive.writestr("shaders/gbuffers_basic.vsh", "void main() {}")
        with patch.object(verify, "CLIENT_DIR", self.results / "client"):
            evidence = verify.stage_shader_fixture("iris-active", shaderpack_file=pack)
        self.assertEqual(evidence["name"], "__ip_verify__RealPack-1.2.zip")
        self.assertEqual(evidence["sha256"], hashlib.sha256(pack.read_bytes()).hexdigest())
        self.assertEqual(evidence["programs"], ["shaders/gbuffers_basic.vsh"])
        self.assertFalse(evidence["diagnostic_fixture"])
        with zipfile.ZipFile(pack, "w") as archive:
            archive.writestr("not-a-pack.txt", "no shaders")
        with patch.object(verify, "CLIENT_DIR", self.results / "client"):
            with self.assertRaisesRegex(RuntimeError, "top-level shaders"):
                verify.stage_shader_fixture("iris-active", shaderpack_file=pack)

    def test_negative_controls_accept_only_the_intended_failure(self):
        root = self.results / "checkout"
        with patch.object(verify, "ROOT", root):
            def fail_with(text):
                def run(**kwargs):
                    verify.RESULT_DIR.mkdir(parents=True, exist_ok=True)
                    (verify.RESULT_DIR / "client-fail.txt").write_text(text)
                    raise RuntimeError(text)
                return run
            with patch.object(verify, "run_e2e", side_effect=fail_with("SHADER_FIXTURE_NOT_ACTIVE")):
                verify.run_visual("iris-active", True, 200, "pack-disabled")
            with patch.object(verify, "run_e2e", side_effect=fail_with("MixinApplyError: unrelated boot failure")):
                with self.assertRaisesRegex(RuntimeError, "unrelated boot failure"):
                    verify.run_visual("iris-active", True, 200, "pack-disabled")
            with patch.object(verify, "run_e2e", side_effect=fail_with("PORTAL_PIXELS_MISMATCH: before-reload/solid-visible")):
                with self.assertRaisesRegex(RuntimeError, "solid-visible"):
                    verify.run_visual("iris-active", True, 200, "clipping-disabled")
            with patch.object(verify, "run_e2e", side_effect=fail_with("PORTAL_PIXELS_MISMATCH: before-reload/solid-clipped")):
                verify.run_visual("iris-active", True, 200, "clipping-disabled")
            with patch.object(verify, "run_e2e"):
                with self.assertRaisesRegex(RuntimeError, "unexpectedly passed"):
                    verify.run_visual("iris-active", True, 200, "clipping-disabled")

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


class ShipVerificationHarnessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.results = Path(self.temp.name)
        patcher = patch.multiple(verify, RESULT_DIR=self.results,
                                 SERVER_LOG=self.results / "server.log", CLIENT_LOG=self.results / "client.log")
        patcher.start()
        self.addCleanup(patcher.stop)

    def reports(self, aeronautics=False, ship_size="all"):
        reports = {side: {"schema_version": 1, "cases": []} for side in ("server", "client")}
        if aeronautics:
            for report in reports.values():
                report["addons"] = dict(verify.aeronautics_bundle.BUNDLE["mods"])
        for size, dimensions, blocks, chunks in (("CONTROL", [7, 5, 11], 189 if aeronautics else 176, 1),
                                                ("LARGE", [49, 17, 65], 4941 if aeronautics else 4185, 20)):
            if ship_size != "all" and size.lower() != ship_size:
                continue
            for route in (("ordinary",) if aeronautics else ("ordinary", "dimension-stack", "vertical-broadside")):
                broadside = route == "vertical-broadside"
                leg_count = 2 if broadside else 4
                common = dict(size=size, route=route, dimensions=dimensions, blocks=blocks, chunks=chunks,
                              assembled=True, ship_id=f"ship-{size}-{route}")
                def snapshot(dimension, removed=0):
                    half_width, half_length = dimensions[0] // 2, dimensions[2] // 2
                    left, right = [-half_width + 1, 1, half_length - 1], [half_width - 1, 1, half_length - 1]
                    manifest = [dict(position=position, slot=slot, item="minecraft:" + item, count=count, components="{}")
                                for position, slot, item, count in ((left, 0, "diamond", 17), (left, 26, "copper_ingot", 31),
                                    (right, 0, "emerald", 18), (right, 26, "copper_ingot", 32), ([0, 1, 3], 0, "diamond", 1))]
                    return dict(shipId=common["ship_id"], size=size, dimension=dimension, assembledBlocks=blocks,
                                currentBlocks=blocks-removed, plotChunks=chunks, plotSections=40 if size == "LARGE" else 1,
                                blockEntities=9, cargoItems=99, assemblyMethod="SubLevelAssemblyHelper.assembleBlocks",
                                cargoManifest=manifest)
                server = dict(common, crossings=[], stale_request_rejected=True,
                              integrity_snapshot=snapshot("minecraft:overworld"))
                if broadside:
                    del server["stale_request_rejected"]
                if aeronautics or broadside:
                    server["prepared_corridor"] = dict(dimension="minecraft:the_nether", complete=True, setup_only=True,
                        min=[-128, 0, -128], max_inclusive=[128, 96, 128], total_positions=6406753,
                        visited_positions=6406753, changed_blocks=4000000, setup_ticks=240,
                        elapsed_ms=12000.0, max_batch_ms=175.0, positions_per_tick_limit=32768, soft_tick_budget_ms=50.0)
                if aeronautics:
                    server["lift_budget"] = dict(mass=1000.0, gravityY=-10.0, weight=10000.0,
                        maximumStaticLift=15000.0, staticLiftWeightRatio=1.5, minimumAirPressure=0.9,
                        atmosphereModified=False, engineCount=8 if size == "CONTROL" else 252,
                        trimMomentX=30.0, trimMomentZ=-40.0)
                case_index = ((4 if size == "CONTROL" else 5) if broadside
                              else (0 if size == "CONTROL" else 2) + (route == "dimension-stack"))
                def mesh(leg):
                    positions = [[1280063 + n, 8, 1280063] for n in range(1 if size == "CONTROL" else 2)]
                    return dict(view="overview", ship_id=common["ship_id"], token=f"{case_index}:{leg}",
                                dimension="minecraft:the_nether" if leg in (0, 2) else "minecraft:overworld",
                                camera_plot_eye=[1280000.5, 130.0, 1280000.5],
                                camera_plot_forward=[0.0, 0.0, 1.0], camera_plot_up=[0.0, 1.0, 0.0],
                                camera_world_eye=[0.5, 130.0, 0.5],
                                requested_camera_plot_eye=[1280000.5, 130.0, 1280000.5],
                                requested_camera_plot_target=[1280000.5, 130.0, 1280001.5],
                                camera_matches_requested=True, camera_segment_clear=True,
                                camera_teleport_animation_running=False, visible_section_positions=positions,
                                vertical_fov_degrees=70.0, aspect_ratio=854/480, view_distance_blocks=160.0,
                                framed_all_exposed_static_sections=True,
                                expected_sections=len(positions), compiled_sections=len(positions),
                                nonempty_sections=len(positions), complete=True, missing_sections=[],
                                expected_section_positions=positions,
                                section_results=[dict(position=p, compiled=True, nonempty=True) for p in positions],
                                screenshot=f"overview-{case_index}-{leg}.png")
                client = dict(common, crossings=[], snapshots=leg_count+1, overview_mesh=mesh(-1),
                              screenshots=[f"{size}-{route}-{phase}.png" for phase in range(leg_count+1)])
                for leg in range(leg_count):
                    worlds = ("minecraft:overworld", "minecraft:the_nether")
                    server["crossings"].append(dict(leg=leg, **{"from": worlds[leg % 2], "to": worlds[(leg + 1) % 2]},
                        native_motion_ticks=20, native_distance=8.0, unique_body=True, rider_graph=True,
                        integrity=True, velocity=True, orientation=True, client_ack=True,
                        integrity_snapshot=snapshot(worlds[(leg+1) % 2], leg % 2)))
                    client["crossings"].append(dict(leg=leg, **{"from": worlds[leg % 2], "to": worlds[(leg + 1) % 2]},
                        unique_body=True, rider_graph=True, blocks=True, machinery=True, mutation=True,
                        panel_pixels=1200, opposing_panel_pixels=40, panel_roi_pixels=5000,
                        blue_panel_pixels=500, yellow_panel_pixels=500,
                        screenshot=client["screenshots"][leg+1], render_completeness=mesh(leg)))
                    if aeronautics:
                        direction = -1 if leg % 2 == 0 else 1
                        server["crossings"][-1].update(powered_thrust=True, propeller_count=8 if size == "CONTROL" else 252,
                            peak_vertical_acceleration=-20.0 if direction < 0 else 5.0,
                            gravity_y=-10.0, force_samples=5, no_native_velocity_writes=True,
                            powered_motion=dict(ticks=10, verticalDisplacement=direction * 2.0,
                                verticalVelocityChange=direction * 4.0, startVerticalVelocity=0.0,
                                endVerticalVelocity=direction * 4.0, dimension=worlds[leg % 2],
                                againstGravity=direction > 0))
                        client["crossings"][-1]["propellers"] = True
                if broadside:
                    server["prepared_source_corridor"] = dict(server["prepared_corridor"],dimension="minecraft:overworld")
                    staging_player = dict(player_id="player-id", original_player_id="player-id", current_player_object=True,
                        alive=True, health=20.0, dimension="minecraft:overworld", position=[112.5,200.0,112.5],
                        platform_center=[112,199,112], platform_radius=2, support_block="minecraft:stone",
                        normal_player_gravity=True, unmounted=True, support_loaded=True, headroom_clear=True,
                        flying=False, game_mode="creative")
                    server["setup_staging"] = dict(staging_player, staging_tick=100, first_preparation_tick=101,
                        last_preparation_tick=580, unmounted_setup_ticks=500, setup_only=True, flight_staging_writes=0,
                        verified_before_mount=dict(staging_player))
                    server["visual_sampling"] = dict(states=["before-launch","after-outbound","after-return"],
                                                     in_flight_stage_screenshots=False)
                    server["broadside_geometry"] = dict(portal_normal=[1,0,0], portal_width=160, portal_height=80,
                        portal_center_y=64, ship_nose_local=[0,0,1], travel_axis_local=[1,0,0], yaw_degrees=90,
                        launch_kind="native-ballistic", flight_pose_writes=0)
                    for crossing in server["crossings"]:
                        leg = crossing["leg"]
                        def hull(center):
                            return dict(min_normal=center-dimensions[0]/2, center_normal=center,
                                max_normal=center+dimensions[0]/2, min_width=-dimensions[2]/2,
                                max_width=dimensions[2]/2, min_height=-dimensions[1]/2, max_height=dimensions[1]/2)
                        centers = [dimensions[0]/2+20, dimensions[0]/2-.5, -.5, -dimensions[0]/2-.5, -dimensions[0]/2-12]
                        distance = centers[0]+dimensions[0]/2+12
                        duration = distance/24
                        upward = 11*duration/2
                        rise = upward*upward/22
                        samples = []
                        for index, center in enumerate(centers):
                            arrived = index >= 2
                            dimension = crossing["to" if arrived else "from"]
                            ow = dimension == "minecraft:overworld"
                            travel = -24.0 if leg == 0 else 24.0
                            local_v = [travel, upward, 0.0]
                            v = local_v if ow else [0.0, upward, -travel]
                            q = [0,0,0,1] if ow else [0,math.sqrt(.5),0,math.sqrt(.5)]
                            normal_center = -center if arrived else center
                            normal_axis = 0 if ow else 2
                            mid = [0.0, 64.0, 0.0]; mid[normal_axis] = normal_center
                            spans = list(dimensions) if ow else [dimensions[2],dimensions[1],dimensions[0]]
                            world_bounds = [mid[i]-spans[i]/2 for i in range(3)] + [mid[i]+spans[i]/2 for i in range(3)]
                            sample = dict(sample=index, dimension=dimension,
                                native_tick=1000+leg*100+index*10, arrived=arrived, ship_id=common["ship_id"], bounds=hull(center),
                                world_bounds=world_bounds,
                                pose=dict(position=mid,orientation=q,rotation_point=[0,0,0],scale=[1,1,1]),
                                native_velocity=v,body_local_velocity=local_v,nose_world=[0,0,1] if ow else [1,0,0],
                                nose_velocity_abs_cosine=0,unique_body=True,rider_graph=True,aperture_fit=True)
                            sample.update(native_distance=centers[0]-center,
                                source_body_count=int(ow), destination_body_count=int(not ow),
                                seat_id=f"broadside-seat-{size}",player_id="player-id",
                                player_dimension=sample["dimension"],player_vehicle_id=f"broadside-seat-{size}",
                                seat_passenger_ids=["player-id"])
                            samples.append(sample)
                        stages = {}
                        for stage, index in (("leading",1),("center",2),("trailing",3)):
                            stages[stage] = dict(samples[index], integrity_snapshot=snapshot(samples[index]["dimension"],leg%2),
                                machinery_snapshot=dict(motorCount=2,expectedRpm=16 if leg==0 else -32,
                                    actualSpeeds=[16 if leg==0 else -32]*6,nativePropulsion=True,aeronauticsPropulsion=False))
                        crossing["broadside_receipt"] = dict(
                            launch=dict(kind="native-ballistic",horizontal_speed=24,gravity_y=-11,universal_drag=0,
                                planned_distance=distance,planned_duration=duration,upward_speed=upward,
                                predicted_max_rise=rise,initial_velocity=list(samples[0]["native_velocity"]),initial_bounds=hull(centers[0])),
                            samples=samples, stages=stages, final_max_normal=-12, clearance_gap=12,
                            whole_hull_cleared=True,aperture_fit=True,no_native_writes_during_flight=True)
                if route == "ordinary":
                    probe = dict(kind="held_body_stopped_source_animation", counted_as_flight_crossing=False,
                                 ship_id=common["ship_id"], seat_id=f"seat-{size}",
                                 **{"from": "minecraft:overworld", "to": "minecraft:the_nether"},
                                 unique_body=True, rider_graph=True, prediction_restored=True)
                    server["stopped_animation_witness"] = dict(probe, driver="NormalAnimation", animation_ticks=20,
                        drained_ticks_before_request=4, ticks_after_sweep=12, native_linear_speed_at_request=0.0,
                        native_angular_speed_at_request=0.0, source_plane_speed_at_request=0.0,
                        zero_native_motion_at_request=True, request_resolved=True, client_ack=True,
                        integrity_snapshot=snapshot("minecraft:the_nether"))
                    client["stopped_animation_witness"] = dict(probe, dimension="minecraft:the_nether",
                                                              blocks=True, machinery=True, mutation=True)
                reports["server"]["cases"].append(server)
                reports["client"]["cases"].append(client)
        for report in reports.values():
            report["cases"].sort(key=lambda c: (c["route"] == "vertical-broadside", c["size"], c["route"] != "ordinary"))
        return reports

    def write_reports(self, reports=None):
        if reports is None:
            reports = self.reports()
        for side, report in reports.items():
            (self.results / f"{side}-ships.json").write_text(json.dumps(report))
        for side in ("server", "client"):
            (self.results / f"{side}-pass.txt").write_text("verified ship flight\n")
        for case in self.reports()["client"]["cases"]:
            overviews = [case["overview_mesh"]] + [c["render_completeness"] for c in case["crossings"]]
            for name in case["screenshots"] + [m["screenshot"] for m in overviews]:
                (self.results / name).write_bytes(base64.b64decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg=="))

    def test_ships_require_independent_reports_without_old_phase_markers(self):
        self.write_reports()
        verify.validate_results(0, ships=True)
        with self.assertRaisesRegex(RuntimeError, "client-source"):
            verify.validate_results(0)
        for side in ("server", "client"):
            report = self.results / f"{side}-ships.json"
            original = report.read_text()
            report.unlink()
            with self.assertRaisesRegex(RuntimeError, "ship flight report"):
                verify.validate_results(0, ships=True)
            report.write_text(original)
            marker = self.results / f"{side}-pass.txt"
            marker.write_text("")
            with self.assertRaisesRegex(RuntimeError, "missing or empty"):
                verify.validate_results(0, ships=True)
            marker.write_text("verified")

    def test_ships_fail_markers_and_actual_exit_override_reports(self):
        self.write_reports()
        for side in ("server", "client"):
            marker = self.results / f"{side}-fail.txt"
            marker.write_text(f"{side} flight failed")
            with self.assertRaisesRegex(RuntimeError, f"{side} flight failed"):
                verify.validate_results(0, ships=True)
            marker.unlink()
        with self.assertRaisesRegex(RuntimeError, "status 1"):
            verify.validate_results(1, ships=True)
        with self.assertRaises(ValueError):
            verify.validate_results(0, smoke=True, ships=True)

    def test_ships_disconnect_and_process_exit_use_ship_validation(self):
        self.write_reports()
        (self.results / "server.log").write_text("Dev lost connection: Disconnected\n")
        server, client = Mock(), Mock()
        server.poll.return_value = None
        client.poll.return_value = client.returncode = 0
        verify.wait_for_client(server, client, ships=True)
        (self.results / "client-ships.json").unlink()
        with patch.object(verify, "collect_thread_diagnostics") as diagnostic:
            with self.assertRaisesRegex(RuntimeError, "graphical client disconnected"):
                verify.wait_for_client(server, client, ships=True)
            diagnostic.assert_called_once_with(server, client, "disconnect")

    def test_ship_reports_reject_invalid_top_level_and_partial_matrix(self):
        self.write_reports()
        for side in ("server", "client"):
            for invalid in ("not JSON", "null", "[]", "{}", '{"schema_version":true,"cases":[]}',
                            '{"schema_version":2,"cases":[]}', '{"schema_version":1,"cases":[]}'):
                with self.subTest(side=side, invalid=invalid):
                    self.write_reports()
                    (self.results / f"{side}-ships.json").write_text(invalid)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports()
            for change in (lambda cases: cases.pop(), lambda cases: cases.append(cases[0]),
                           lambda cases: cases.__setitem__(1, cases[0]), lambda cases: cases.__setitem__(0, None)):
                reports = self.reports()
                change(reports[side]["cases"])
                self.write_reports(reports)
                with self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()

    def test_ship_reports_reject_false_missing_or_truthy_crossing_claims(self):
        for side, claims in (("server", ("unique_body", "rider_graph", "integrity", "velocity", "orientation", "client_ack")),
                             ("client", ("unique_body", "rider_graph", "blocks", "machinery", "mutation"))):
            for case_index in range(4):
                for leg in range(4):
                    for claim in claims:
                        for value in (None, False, "true", 1):
                            with self.subTest(side=side, case=case_index, leg=leg, claim=claim, value=value):
                                reports = self.reports()
                                crossing = reports[side]["cases"][case_index]["crossings"][leg]
                                if value is None:
                                    del crossing[claim]
                                else:
                                    crossing[claim] = value
                                self.write_reports(reports)
                                with self.assertRaisesRegex(RuntimeError, "proof"):
                                    verify.validate_ship_reports()

    def test_ship_reports_reject_incomplete_or_inconsistent_fixture_identity(self):
        for side in ("server", "client"):
            for field, value in (("size", "small"), ("route", "teleport"), ("assembled", False),
                                 ("assembled", 1), ("blocks", 0), ("blocks", True), ("chunks", 0),
                                 ("dimensions", [49, 1, 65]), ("dimensions", [49, 17.0, 65]),
                                 ("ship_id", ""), ("ship_id", 1), ("crossings", []),
                                 ("crossings", [None] * 4)):
                with self.subTest(side=side, field=field, value=value):
                    reports = self.reports()
                    reports[side]["cases"][2][field] = value
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports()
        for field, value in (("blocks", 3499), ("chunks", 11)):
            reports = self.reports()
            reports["server"]["cases"][2][field] = value
            self.write_reports(reports)
            with self.assertRaises(RuntimeError):
                verify.validate_ship_reports()
        reports = self.reports()
        reports["client"]["cases"][0]["ship_id"] = "different-ship"
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "client/server fixture mismatch"):
            verify.validate_ship_reports()
        reports = self.reports()
        reports["server"]["cases"][1]["ship_id"] = reports["server"]["cases"][0]["ship_id"]
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "reused fixture"):
            verify.validate_ship_reports()

    def test_ship_reports_reject_teleport_only_or_nonfinite_motion_and_wrong_legs(self):
        for field, values in (("native_motion_ticks", (None, 0, 4, -1, True, 1.5, "20")),
                              ("native_distance", (None, 0, 7.99, -1, True, float("nan"), float("inf"), "8")),
                              ("from", ("minecraft:the_nether", None)),
                              ("to", ("minecraft:overworld", None)),
                              ("leg", (True, 1, "0", None))):
            for value in values:
                with self.subTest(field=field, value=value):
                    reports = self.reports()
                    reports["server"]["cases"][0]["crossings"][0][field] = value
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports()
        reports = self.reports()
        reports["client"]["cases"][0]["crossings"].reverse()
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "out-of-order"):
            verify.validate_ship_reports()

    def test_broadside_adds_exact_baseline_cases_legs_and_graphical_observations(self):
        for size in ("all", "control", "large"):
            reports = self.reports(ship_size=size)
            for side in ("server", "client"):
                cases = reports[side]["cases"]
                self.assertEqual(len(cases), 6 if size == "all" else 3)
                self.assertEqual(sum(len(c["crossings"]) for c in cases), 20 if size == "all" else 10)
                for case in cases:
                    broadside = case["route"] == "vertical-broadside"
                    self.assertEqual(len(case["crossings"]), 2 if broadside else 4)
                    if broadside:
                        self.assertNotIn("stale_request_rejected", case)
                        self.assertNotIn("stopped_animation_witness", case)
                    if side == "client":
                        self.assertEqual(case["snapshots"], 3 if broadside else 5)
            self.write_reports(reports)
            verify.validate_ship_reports(ship_size=size)
        for side in ("server", "client"):
            reports = self.reports()
            reports[side]["cases"] = reports[side]["cases"][:4]
            self.write_reports(reports)
            with self.assertRaisesRegex(RuntimeError, "expected 6 ship cases"):
                verify.validate_ship_reports()
        powered = self.reports(aeronautics=True)
        self.assertEqual(len(powered["server"]["cases"]), 2)
        self.assertEqual(sum(len(c["crossings"]) for c in powered["server"]["cases"]), 8)

    def test_broadside_rejects_wrong_leg_counts_and_reused_observation_tokens(self):
        for side in ("server", "client"):
            for index in (4, 5):
                for count in (0, 1, 3, 4):
                    reports = self.reports()
                    case = reports[side]["cases"][index]
                    case["crossings"] = (case["crossings"] * 2)[:count]
                    self.write_reports(reports)
                    with self.subTest(side=side, case=index, count=count), self.assertRaisesRegex(RuntimeError, "2 crossing legs"):
                        verify.validate_ship_reports()
        for index in (4, 5):
            for leg in (-1, 0, 1):
                reports = self.reports()
                case = reports["client"]["cases"][index]
                receipt = case["overview_mesh"] if leg < 0 else case["crossings"][leg]["render_completeness"]
                receipt["token"] = f"{index - 4}:{leg}"
                self.write_reports(reports)
                with self.subTest(case=index, leg=leg), self.assertRaisesRegex(RuntimeError, "identity/token"):
                    verify.validate_ship_reports()
        for snapshots in (2, 4, 5):
            reports = self.reports()
            reports["client"]["cases"][4]["snapshots"] = snapshots
            self.write_reports(reports)
            with self.assertRaisesRegex(RuntimeError, "source and 2 arrival snapshots"):
                verify.validate_ship_reports()

    def test_broadside_requires_numeric_geometry_both_corridors_and_honest_visual_scope(self):
        for index in (4, 5):
            for field in ("broadside_geometry", "prepared_corridor", "prepared_source_corridor", "visual_sampling"):
                reports = self.reports()
                del reports["server"]["cases"][index][field]
                self.write_reports(reports)
                with self.subTest(case=index, field=field), self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()
        for field, value in (("portal_normal", [0,1,0]), ("portal_normal", [True,0,0]),
                             ("ship_nose_local", [1,0,0]), ("travel_axis_local", [0,0,1]),
                             ("portal_width", 159), ("portal_height", 81), ("portal_center_y", 65),
                             ("yaw_degrees", 0), ("flight_pose_writes", 1), ("flight_pose_writes", False),
                             ("launch_kind", "teleport")):
            reports = self.reports()
            reports["server"]["cases"][5]["broadside_geometry"][field] = value
            self.write_reports(reports)
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                verify.validate_ship_reports()
        reports = self.reports()
        reports["server"]["cases"][4]["visual_sampling"]["in_flight_stage_screenshots"] = True
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "graphical scope"):
            verify.validate_ship_reports()
        reports = self.reports()
        reports["server"]["cases"][4]["prepared_source_corridor"]["dimension"] = "minecraft:the_nether"
        self.write_reports(reports)
        with self.assertRaises(RuntimeError):
            verify.validate_ship_reports()

    def test_broadside_staging_requires_live_original_player_on_real_support(self):
        invalid = (("player_id", ""), ("original_player_id", "replacement-player"), ("current_player_object", False),
                   ("alive", False), ("alive", 1), ("health", 0), ("health", float("nan")), ("health", True),
                   ("normal_player_gravity", False), ("unmounted", False), ("support_loaded", False),
                   ("headroom_clear", False), ("flying", True), ("game_mode", "spectator"),
                   ("dimension", "minecraft:the_nether"), ("support_block", "minecraft:air"),
                   ("platform_center", [112,96,112]), ("platform_radius", True), ("platform_radius", 1),
                   ("position", [112.5,199,112.5]), ("position", [115,200,112.5]),
                   ("position", [112.5,200,float("inf")]))
        for index in (4, 5):
            for snapshot in (None, "verified_before_mount"):
                reports = self.reports()
                staging = reports["server"]["cases"][index]
                if snapshot is None: del staging["setup_staging"]
                else: del staging["setup_staging"][snapshot]
                self.write_reports(reports)
                with self.subTest(case=index, missing=snapshot), self.assertRaisesRegex(RuntimeError, "staging"):
                    verify.validate_ship_reports()
                for field, value in invalid:
                    reports = self.reports()
                    staging = reports["server"]["cases"][index]["setup_staging"]
                    if snapshot: staging = staging[snapshot]
                    staging[field] = value
                    self.write_reports(reports)
                    with self.subTest(case=index, snapshot=snapshot, field=field), self.assertRaisesRegex(RuntimeError, "staging"):
                        verify.validate_ship_reports()

    def test_broadside_staging_binds_preparation_timing_and_flight_player_identity(self):
        for field, value in (("setup_only", False), ("flight_staging_writes", 1), ("flight_staging_writes", False),
                             ("staging_tick", -1), ("first_preparation_tick", True), ("first_preparation_tick", 100),
                             ("last_preparation_tick", 579), ("last_preparation_tick", 600),
                             ("unmounted_setup_ticks", 480), ("unmounted_setup_ticks", None)):
            reports = self.reports()
            reports["server"]["cases"][5]["setup_staging"][field] = value
            self.write_reports(reports)
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, "staging"):
                verify.validate_ship_reports()
        reports = self.reports()
        staging = reports["server"]["cases"][4]["setup_staging"]
        staging["verified_before_mount"].update(player_id="new-player", original_player_id="new-player")
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "staging player changed"):
            verify.validate_ship_reports()
        reports = self.reports()
        staging = reports["server"]["cases"][4]["setup_staging"]
        staging.update(player_id="new-player", original_player_id="new-player")
        staging["verified_before_mount"].update(player_id="new-player", original_player_id="new-player")
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "broadside rider graph"):
            verify.validate_ship_reports()
        reports = self.reports()
        reports["server"]["cases"][4]["crossings"][0]["broadside_receipt"]["samples"][0]["native_tick"] = 600
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "native launch precedes"):
            verify.validate_ship_reports()

    def test_broadside_rejects_incomplete_launch_or_flight_receipts_in_each_leg(self):
        invalid = ((["whole_hull_cleared"], False), (["aperture_fit"], 1),
                   (["no_native_writes_during_flight"], None), (["clearance_gap"], 0),
                   (["launch"], None), (["launch","kind"], "teleport"),
                   (["launch","horizontal_speed"], 0), (["launch","gravity_y"], 0),
                   (["launch","universal_drag"], -1), (["launch","planned_distance"], 1),
                   (["launch","planned_duration"], 1), (["launch","predicted_max_rise"], float("nan")),
                   (["launch","upward_speed"], 25), (["launch","initial_velocity"], [24,8,0]),
                   (["samples"], []), (["samples"], None), (["stages"], {}),
                   (["final_max_normal"], -11.9), (["final_max_normal"], float("inf")))
        for index in (4, 5):
            for leg in (0, 1):
                for path, value in invalid:
                    reports = self.reports()
                    receipt = reports["server"]["cases"][index]["crossings"][leg]["broadside_receipt"]
                    target = receipt
                    for part in path[:-1]: target = target[part]
                    target[path[-1]] = value
                    self.write_reports(reports)
                    with self.subTest(case=index, leg=leg, path=path, value=value), self.assertRaises(RuntimeError):
                        verify.validate_ship_reports()

    def test_broadside_native_samples_bind_full_hull_pose_motion_and_live_rider_ownership(self):
        invalid = ((["sample"], True), (["sample"], 0), (["native_tick"], 100),
                   (["ship_id"], "clone"), (["dimension"], "other:dimension"), (["arrived"], 1),
                   (["native_distance"], -1), (["source_body_count"], 1), (["destination_body_count"], 2),
                   (["seat_id"], "another-seat"), (["player_id"], "another-player"),
                   (["player_dimension"], "other:dimension"), (["player_vehicle_id"], "other-seat"),
                   (["seat_passenger_ids"], []), (["seat_passenger_ids"], ["player-id","extra-rider"]),
                   (["unique_body"], False), (["rider_graph"], "true"), (["aperture_fit"], 1),
                   (["native_velocity"], [0,24,0]), (["body_local_velocity"], [24,0,0]),
                   (["nose_world"], [0,0,1]), (["nose_velocity_abs_cosine"], .025),
                   (["bounds","min_width"], -79), (["bounds","max_height"], 39),
                   (["bounds","center_normal"], 0), (["bounds","max_normal"], float("nan")),
                   (["pose","scale"], [.1,.1,.1]), (["pose","orientation"], [0,0,0,1]),
                   (["pose","position"], [0,float("inf"),0]),
                   (["world_bounds"], [-1,63,-1,1,65,1]))
        for path, value in invalid:
            reports = self.reports()
            target = reports["server"]["cases"][5]["crossings"][0]["broadside_receipt"]["samples"][2]
            for part in path[:-1]: target = target[part]
            target[path[-1]] = value
            self.write_reports(reports)
            with self.subTest(path=path, value=value), self.assertRaises(RuntimeError):
                verify.validate_ship_reports()
        reports = self.reports()
        sample = reports["server"]["cases"][5]["crossings"][0]["broadside_receipt"]["samples"][4]
        sample["arrived"] = False
        self.write_reports(reports)
        with self.assertRaises(RuntimeError):
            verify.validate_ship_reports()

    def test_broadside_return_cannot_substitute_a_different_rider_or_seat(self):
        for field in ("seat_id", "player_id"):
            reports = self.reports()
            receipt = reports["server"]["cases"][5]["crossings"][1]["broadside_receipt"]
            for sample in receipt["samples"] + list(receipt["stages"].values()):
                sample[field] = "different-identity"
                if field == "seat_id": sample["player_vehicle_id"] = "different-identity"
                else: sample["seat_passenger_ids"] = ["different-identity"]
            self.write_reports(reports)
            with self.subTest(field=field), self.assertRaisesRegex(RuntimeError, "broadside rider graph"):
                verify.validate_ship_reports()

    def test_broadside_measured_seam_velocity_and_orientation_must_transform(self):
        reports = self.reports()
        sample = reports["server"]["cases"][5]["crossings"][0]["broadside_receipt"]["samples"][2]
        sample["native_velocity"][1] += 6
        sample["body_local_velocity"][1] += 6
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "seam velocity"):
            verify.validate_ship_reports()
        reports = self.reports()
        sample = reports["server"]["cases"][5]["crossings"][0]["broadside_receipt"]["samples"][2]
        angle = math.radians(100)
        sample["pose"]["orientation"][:] = [0,math.sin(angle/2),0,math.cos(angle/2)]
        sample["nose_world"][:] = [math.sin(angle),0,math.cos(angle)]
        local = sample["body_local_velocity"]
        sample["native_velocity"][:] = [local[0]*math.cos(angle),local[1],-local[0]*math.sin(angle)]
        width = 49*abs(math.cos(angle))+65*abs(math.sin(angle))
        length = 49*abs(math.sin(angle))+65*abs(math.cos(angle))
        sample["world_bounds"][:] = [-width/2,55.5,.5-length/2,width/2,72.5,.5+length/2]
        sample["bounds"].update(min_normal=-.5-length/2,max_normal=-.5+length/2,
                                min_width=-width/2,max_width=width/2)
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "seam orientation"):
            verify.validate_ship_reports()

    def test_broadside_stages_must_be_distinct_actual_samples_with_exact_cargo_and_machinery(self):
        for stage in ("leading", "center", "trailing"):
            for field, value in (("sample", 0), ("sample", True), ("native_tick", 999),
                                 ("integrity_snapshot", None), ("machinery_snapshot", None)):
                reports = self.reports()
                reports["server"]["cases"][4]["crossings"][1]["broadside_receipt"]["stages"][stage][field] = value
                self.write_reports(reports)
                with self.subTest(stage=stage, field=field), self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()
            for field, value in (("motorCount", 1), ("motorCount", True), ("expectedRpm", 16),
                                 ("actualSpeeds", [-32]*5), ("actualSpeeds", [-32]*5+[16]),
                                 ("nativePropulsion", 1), ("aeronauticsPropulsion", True)):
                reports = self.reports()
                receipt = reports["server"]["cases"][4]["crossings"][1]["broadside_receipt"]["stages"][stage]
                receipt["machinery_snapshot"][field] = value
                self.write_reports(reports)
                with self.subTest(stage=stage, field=field), self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()
            reports = self.reports()
            receipt = reports["server"]["cases"][5]["crossings"][0]["broadside_receipt"]["stages"][stage]
            receipt["integrity_snapshot"]["cargoManifest"][0]["components"] = "changed"
            self.write_reports(reports)
            with self.assertRaisesRegex(RuntimeError, "cargo discontinuity"):
                verify.validate_ship_reports()
        reports = self.reports()
        stages = reports["server"]["cases"][5]["crossings"][0]["broadside_receipt"]["stages"]
        stages["center"] = stages["leading"]
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "unordered broadside hull stages"):
            verify.validate_ship_reports()

    def test_ship_reports_require_exact_fixture_counts_for_each_mode(self):
        for addon in (False, True):
            for side in ("server", "client"):
                for index in range(len(self.reports(addon)[side]["cases"])):
                    for delta in (-1, 1):
                        reports = self.reports(addon)
                        reports[side]["cases"][index]["blocks"] += delta
                        self.write_reports(reports)
                        with self.subTest(addon=addon, side=side, case=index, delta=delta), self.assertRaisesRegex(RuntimeError, "block count"):
                            verify.validate_ship_reports(aeronautics=addon)

    def test_powered_corridor_requires_exact_completed_setup_receipt(self):
        for size in ("control", "large"):
            for receipt in (None, {}, False):
                with self.subTest(size=size, receipt=receipt):
                    reports = self.reports(aeronautics=True, ship_size=size)
                    reports["server"]["cases"][0]["prepared_corridor"] = receipt
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports(aeronautics=True, ship_size=size)
        invalid = {
            "dimension": (None, "minecraft:overworld"), "complete": (None, False, 1, "true"),
            "setup_only": (None, False, 1, "true"),
            "min": (None, [-128, 0], [-127, 0, -128], [-128, False, -128]),
            "max_inclusive": (None, [128, 95, 128], [128.0, 96, 128]),
            "total_positions": (None, True, 6406752, 6406753.0, "6406753"),
            "visited_positions": (None, True, 6406752, 6406754, 6406753.0),
            "changed_blocks": (None, True, -1, 6406754, 4.0),
            "setup_ticks": (None, True, 0, 195, 6406754, 240.0),
            "positions_per_tick_limit": (None, True, 32767, 32769, 32768.0),
            "soft_tick_budget_ms": (None, True, 0, 49, 51, float("nan")),
            "elapsed_ms": (None, True, 0, -1, 100.0, float("inf")),
            "max_batch_ms": (None, True, 0, -1, 12001.0, float("nan")),
        }
        for field, values in invalid.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    reports = self.reports(aeronautics=True)
                    reports["server"]["cases"][1]["prepared_corridor"][field] = value
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports(aeronautics=True)

    def test_powered_corridor_preserves_soft_budget_overshoots_as_measured_evidence(self):
        for maximum in (25.0, 50.0, 175.0, 6000.0, 12000.0):
            with self.subTest(max_batch_ms=maximum):
                reports = self.reports(aeronautics=True)
                for case in reports["server"]["cases"]:
                    case["prepared_corridor"].update(changed_blocks=0, setup_ticks=196, max_batch_ms=maximum)
                self.write_reports(reports)
                verify.validate_ship_reports(aeronautics=True)
        self.write_reports()  # Baseline native flight does not use powered terrain setup.
        verify.validate_ship_reports()

    def test_ship_reports_require_server_stale_request_regression(self):
        for addon in (False, True):
            for value in (None, False, 1, "true"):
                reports = self.reports(addon)
                reports["server"]["cases"][0]["stale_request_rejected"] = value
                self.write_reports(reports)
                with self.assertRaisesRegex(RuntimeError, "stale request rejection"):
                    verify.validate_ship_reports(aeronautics=addon)

    def test_stopped_animation_probe_is_required_only_for_every_ordinary_case(self):
        for addon in (False, True):
            reports = self.reports(addon)
            self.assertTrue(all("stopped_animation_witness" not in case for side in reports.values()
                                for case in side["cases"] if case["route"] == "dimension-stack"))
            self.write_reports(reports)
            verify.validate_ship_reports(aeronautics=addon)
            for side in ("server", "client"):
                for index, case in enumerate(reports[side]["cases"]):
                    if case["route"] != "ordinary":
                        continue
                    for value in (None, True, [], {}):
                        broken = self.reports(addon)
                        broken[side]["cases"][index]["stopped_animation_witness"] = value
                        self.write_reports(broken)
                        with self.subTest(addon=addon, side=side, case=index, value=value), self.assertRaises(RuntimeError):
                            verify.validate_ship_reports(aeronautics=addon)

    def test_stopped_animation_probe_requires_literal_success_and_separate_flight_accounting(self):
        common = ("unique_body", "rider_graph", "prediction_restored")
        for side, extra in (("server", ("zero_native_motion_at_request", "request_resolved", "client_ack")),
                            ("client", ("blocks", "machinery", "mutation"))):
            for claim in common + extra:
                for value in (None, False, "true", 1):
                    reports = self.reports()
                    reports[side]["cases"][0]["stopped_animation_witness"][claim] = value
                    self.write_reports(reports)
                    with self.subTest(side=side, claim=claim, value=value), self.assertRaisesRegex(RuntimeError, "proof"):
                        verify.validate_ship_reports()
            for value in (None, True, 0, "false"):
                reports = self.reports()
                reports[side]["cases"][0]["stopped_animation_witness"]["counted_as_flight_crossing"] = value
                self.write_reports(reports)
                with self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()

    def test_stopped_animation_probe_requires_measured_exact_zero_and_bounded_delay(self):
        fields = [(name, value) for name in ("native_linear_speed_at_request", "native_angular_speed_at_request",
                                           "source_plane_speed_at_request")
                  for value in (None, True, "0", -1, 1e-20, float("nan"), float("inf"))]
        fields += [("driver", "native_velocity"), ("animation_ticks", True), ("animation_ticks", 20.0),
                   ("animation_ticks", 19), ("drained_ticks_before_request", 3),
                   ("drained_ticks_before_request", 4.0), ("drained_ticks_before_request", True),
                   ("drained_ticks_before_request", 13), ("ticks_after_sweep", 2),
                   ("ticks_after_sweep", 101), ("ticks_after_sweep", 12.0), ("ticks_after_sweep", True)]
        for addon in (False, True):
            for field, value in fields:
                reports = self.reports(addon)
                reports["server"]["cases"][0]["stopped_animation_witness"][field] = value
                self.write_reports(reports)
                with self.subTest(addon=addon, field=field, value=value), self.assertRaises(RuntimeError):
                    verify.validate_ship_reports(aeronautics=addon)
            reports = self.reports(addon)
            reports["server"]["cases"][0]["stopped_animation_witness"]["ticks_after_sweep"] = 100
            self.write_reports(reports)
            verify.validate_ship_reports(aeronautics=addon)

    def test_stopped_animation_probe_binds_both_sides_and_preserves_exact_cargo(self):
        for side in ("server", "client"):
            for field, value in (("kind", "ordinary_flight"), ("ship_id", "different-ship"), ("seat_id", ""),
                                 ("seat_id", True), ("from", "minecraft:the_nether"), ("to", "minecraft:overworld")):
                reports = self.reports()
                reports[side]["cases"][0]["stopped_animation_witness"][field] = value
                self.write_reports(reports)
                with self.subTest(side=side, field=field, value=value), self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()
        for field, value in (("seat_id", "different-seat"), ("dimension", "minecraft:overworld")):
            reports = self.reports()
            reports["client"]["cases"][0]["stopped_animation_witness"][field] = value
            self.write_reports(reports)
            with self.assertRaisesRegex(RuntimeError, "client/server stopped-animation mismatch"):
                verify.validate_ship_reports()
        for field, value in (("dimension", "minecraft:overworld"), ("currentBlocks", 175), ("cargoItems", 98),
                             ("plotSections", 2), ("blockEntities", 10), ("cargoManifest", [])):
            reports = self.reports()
            reports["server"]["cases"][0]["stopped_animation_witness"]["integrity_snapshot"][field] = value
            self.write_reports(reports)
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                verify.validate_ship_reports()
        reports = self.reports()
        reports["server"]["cases"][0]["stopped_animation_witness"]["integrity_snapshot"]["cargoManifest"][0]["components"] = "{changed}"
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "integrity/cargo mismatch"):
            verify.validate_ship_reports()

    def test_every_server_integrity_snapshot_is_required_and_typed(self):
        for addon in (False, True):
            for location in range(-1, 4):
                for field, value in ((None, None), (None, True), (None, []), ("shipId", "another-ship"),
                                     ("size", "CONTROL"), ("dimension", "other:world"), ("assembledBlocks", 1),
                                     ("currentBlocks", 0), ("plotChunks", True), ("plotSections", True),
                                     ("plotSections", 20), ("plotSections", 41), ("plotSections", 20.5),
                                     ("blockEntities", 8), ("blockEntities", 10000), ("blockEntities", True),
                                     ("cargoItems", 98), ("cargoItems", 99.0), ("assemblyMethod", "teleport")):
                    reports = self.reports(addon)
                    case = reports["server"]["cases"][1 if addon else 3]
                    holder = case if location == -1 else case["crossings"][location]
                    if field is None:
                        holder["integrity_snapshot"] = value
                    else:
                        holder["integrity_snapshot"][field] = value
                    self.write_reports(reports)
                    with self.subTest(addon=addon, location=location, field=field, value=value), self.assertRaises(RuntimeError):
                        verify.validate_ship_reports(aeronautics=addon)

    def test_integrity_requires_arrival_dimension_and_pre_mutation_nonair_counts(self):
        for leg in range(4):
            for field, value in (("dimension", ("minecraft:overworld", "minecraft:the_nether")[leg % 2]),
                                 ("currentBlocks", 176 - (1 - leg % 2)), ("assembledBlocks", 176.0),
                                 ("plotSections", 2), ("blockEntities", 10)):
                reports = self.reports()
                reports["server"]["cases"][0]["crossings"][leg]["integrity_snapshot"][field] = value
                self.write_reports(reports)
                with self.subTest(leg=leg, field=field), self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()

    def test_integrity_requires_exact_cargo_slots_items_counts_and_components(self):
        edits = (lambda manifest: manifest.pop(), lambda manifest: manifest.append(manifest[0]),
                 lambda manifest: manifest.__setitem__(1, manifest[0]), lambda manifest: manifest.__setitem__(0, None),
                 lambda manifest: manifest[0].update(position=[-2.0, 1, 4]),
                 lambda manifest: manifest[0].update(position=[-2, 1, 5]),
                 lambda manifest: manifest[0].update(slot=True), lambda manifest: manifest[0].update(slot=1),
                 lambda manifest: manifest[0].update(item="minecraft:emerald"),
                 lambda manifest: manifest[0].update(count=17.0), lambda manifest: manifest[0].update(count=16),
                 lambda manifest: manifest[0].update(components=None), lambda manifest: manifest[0].update(components=" "),
                 lambda manifest: manifest[0].update(components="{changed:component}"))
        for location in range(-1, 4):
            for index, edit in enumerate(edits):
                reports = self.reports()
                case = reports["server"]["cases"][0]
                snapshot = (case if location == -1 else case["crossings"][location])["integrity_snapshot"]
                edit(snapshot["cargoManifest"])
                self.write_reports(reports)
                with self.subTest(location=location, edit=index), self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()
        # Inventory map iteration order is immaterial; all positions, slots and components must agree.
        reports = self.reports()
        for case in reports["server"]["cases"]:
            for crossing in case["crossings"]:
                crossing["integrity_snapshot"]["cargoManifest"].reverse()
        self.write_reports(reports)
        verify.validate_ship_reports()

    def test_ship_reports_require_case_bound_graphical_artifacts_and_snapshots(self):
        for field, values in (("snapshots", (None, True, 0, 3, 4, 6)),
                              ("screenshots", (None, [], ["../other.png"], ["/other.png"], ["missing.png"],
                                               ["CONTROL-dimension-stack-0.png"], [None]))):
            for value in values:
                with self.subTest(field=field, value=value):
                    reports = self.reports()
                    reports["client"]["cases"][0][field] = value
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports()
        self.write_reports()
        (self.results / "CONTROL-ordinary-0.png").write_bytes(b"not an image")
        with self.assertRaisesRegex(RuntimeError, "invalid screenshot"):
            verify.validate_ship_reports()

    def test_ship_overviews_are_required_at_source_and_every_crossing_in_both_modes(self):
        for addon in (False, True):
            reports = self.reports(aeronautics=addon)
            for index in range(len(reports["client"]["cases"])):
                for leg in range(-1, len(reports["client"]["cases"][index]["crossings"])):
                    with self.subTest(addon=addon, case=index, leg=leg):
                        reports = self.reports(aeronautics=addon)
                        case = reports["client"]["cases"][index]
                        owner = case if leg < 0 else case["crossings"][leg]
                        del owner["overview_mesh" if leg < 0 else "render_completeness"]
                        self.write_reports(reports)
                        with self.assertRaisesRegex(RuntimeError, "missing overview mesh receipt"):
                            verify.validate_ship_reports(aeronautics=addon)

    def test_ship_overview_receipts_bind_identity_token_dimension_and_complete_counts(self):
        invalid = {
            "view": (None, "panel"), "ship_id": (None, "other-ship"),
            "token": (None, "0:-1", "2:1"), "dimension": (None, "minecraft:overworld"),
            "expected_sections": (None, True, 0, -1, 41, 2.0, "2"),
            "compiled_sections": (None, True, 0, 1, 3, 2.0, "2"),
            "nonempty_sections": (None, True, 0, 1, 3, 2.0, "2"),
            "complete": (None, False, 1, "true"),
            "framed_all_exposed_static_sections": (None, False, 1, "true"),
            "camera_plot_eye": (None, [1, 2], [1, 2, float("nan")], [True, 0, 0]),
            "camera_plot_forward": (None, [0, 0, 0], [0, 1, 0], [float("inf"), 0, 0]),
            "camera_plot_up": (None, [0, 0, 0], [0, 0, 1], [float("nan"), 0, 0]),
            "vertical_fov_degrees": (None, True, 0, 180, float("nan"), "70"),
            "aspect_ratio": (None, True, 0, -1, float("inf"), "1.5"),
            "view_distance_blocks": (None, True, 0, -1, float("nan"), "160"),
            "missing_sections": (None, False, {}, [[1280063, 8, 1280063]]),
            "screenshot": (None, "overview-2--1.png", "../overview-2-0.png", "CONTROL-ordinary-0.png"),
        }
        for field, values in invalid.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    reports = self.reports()
                    reports["client"]["cases"][2]["crossings"][0]["render_completeness"][field] = value
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports()

    def test_ship_overview_camera_guards_are_required_for_all_observations(self):
        guards = (("camera_matches_requested", (None, False, 1, "true")),
                  ("camera_segment_clear", (None, False, 1, "true")),
                  ("camera_teleport_animation_running", (None, True, 0, "false")),
                  ("camera_segment_portal", ("other-portal", False, [], {})))
        for addon in (False, True):
            for leg in range(-1, 4):
                for field, values in guards:
                    for value in values:
                        with self.subTest(addon=addon, leg=leg, field=field, value=value):
                            reports = self.reports(aeronautics=addon)
                            case = reports["client"]["cases"][0]
                            receipt = case["overview_mesh"] if leg < 0 else case["crossings"][leg]["render_completeness"]
                            receipt[field] = value
                            self.write_reports(reports)
                            with self.assertRaisesRegex(RuntimeError, "unsafe or unbound overview camera"):
                                verify.validate_ship_reports(aeronautics=addon)
        reports = self.reports()
        reports["client"]["cases"][0]["overview_mesh"]["camera_segment_portal"] = None
        self.write_reports(reports)
        verify.validate_ship_reports()  # Gson can emit null or omit a clear segment's portal field.

    def test_ship_overview_camera_numeric_evidence_must_match_requested_pose(self):
        for field in ("camera_world_eye", "requested_camera_plot_eye", "requested_camera_plot_target"):
            for value in (None, [], [1, 2], [True, 2, 3], [1, 2, float("nan")], [1, 2, float("inf")]):
                with self.subTest(field=field, value=value):
                    reports = self.reports()
                    reports["client"]["cases"][0]["overview_mesh"][field] = value
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports()
        for offset, direction, accepted in ((.25, [0, 0, 1], True), (.251, [0, 0, 1], False),
                                             (0, [0, 0, -1], False), (0, [0, 0, 0], False),
                                             (0, [.01, 0, 1], True), (0, [.1, 0, 1], False)):
            with self.subTest(offset=offset, direction=direction):
                reports = self.reports()
                receipt = reports["client"]["cases"][0]["overview_mesh"]
                receipt["camera_plot_eye"][0] += offset
                receipt["camera_plot_forward"] = direction
                self.write_reports(reports)
                if accepted:
                    verify.validate_ship_reports()
                else:
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports()

    def test_ship_overview_visible_set_cannot_omit_repeat_or_replace_expected_sections(self):
        for positions in (None, [], [[1280063, 8, 1280063]],
                          [[1280063, 8, 1280063], [1280063, 8, 1280063]],
                          [[1280063, 8, 1280063], [0, 0, 0]],
                          [[1280063, 8, 1280063], [True, 8, 1280063]]):
            with self.subTest(positions=positions):
                reports = self.reports()
                reports["client"]["cases"][2]["overview_mesh"]["visible_section_positions"] = positions
                self.write_reports(reports)
                with self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()
        reports = self.reports()
        receipt = reports["client"]["cases"][2]["overview_mesh"]
        receipt["visible_section_positions"] = list(reversed(receipt["expected_section_positions"]))
        self.write_reports(reports)
        verify.validate_ship_reports()

    def test_ship_overview_section_sets_reject_missing_duplicate_extra_and_malformed_entries(self):
        invalid_sets = (None, [], [[1, 2, 3]], [[1, 2, 3], [1, 2, 3]],
                        [[1, 2, 3], None], [[1, 2, 3], [1, 2]], [[1, 2, 3], [True, 2, 3]],
                        [[1, 2, 3], [1, 2, 3.0]], [[1, 2, 3], [1, 2, 2**31]])
        for positions in invalid_sets:
            with self.subTest(positions=positions):
                reports = self.reports()
                reports["client"]["cases"][2]["overview_mesh"]["expected_section_positions"] = positions
                self.write_reports(reports)
                with self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()
        for mode in ("missing", "empty", "duplicate", "extra", "wrong", "malformed", "not_compiled", "empty_mesh", "truthy"):
            with self.subTest(mode=mode):
                reports = self.reports()
                receipt = reports["client"]["cases"][2]["overview_mesh"]
                results = receipt["section_results"]
                if mode == "missing": del receipt["section_results"]
                elif mode == "empty": receipt["section_results"] = []
                elif mode == "duplicate": results[1] = dict(results[0])
                elif mode == "extra": results.append(dict(position=[1, 2, 3], compiled=True, nonempty=True))
                elif mode == "wrong": results[1]["position"] = [1, 2, 3]
                elif mode == "malformed": results[1] = None
                elif mode == "not_compiled": results[1]["compiled"] = False
                elif mode == "empty_mesh": results[1]["nonempty"] = False
                else: results[1]["compiled"] = 1
                self.write_reports(reports)
                with self.assertRaises(RuntimeError):
                    verify.validate_ship_reports()
        reports = self.reports()
        reports["client"]["cases"][2]["overview_mesh"]["section_results"].reverse()
        self.write_reports(reports)
        verify.validate_ship_reports()  # Set identity is independent of iteration order.

    def test_ship_overview_pngs_cannot_be_missing_corrupt_redirected_or_reused(self):
        for leg in range(-1, 4):
            for invalid in ("missing", "truncated", "symlink"):
                with self.subTest(leg=leg, invalid=invalid):
                    self.write_reports()
                    screenshot = self.results / f"overview-2-{leg}.png"
                    if screenshot.is_symlink(): screenshot.unlink()
                    if invalid == "missing": screenshot.unlink()
                    elif invalid == "truncated": screenshot.write_bytes(screenshot.read_bytes()[:24])
                    else:
                        screenshot.unlink()
                        screenshot.symlink_to(self.results / "CONTROL-ordinary-0.png")
                    with self.assertRaisesRegex(RuntimeError, "missing ship flight screenshot|invalid screenshot|redirected screenshot"):
                        verify.validate_ship_reports()
                    screenshot.unlink(missing_ok=True)
        reports = self.reports()
        reports["client"]["cases"][0]["screenshots"][0] = "overview-0--1.png"
        self.write_reports(reports)
        with self.assertRaisesRegex(RuntimeError, "reused screenshot"):
            verify.validate_ship_reports()

    def test_ship_crossing_requires_measured_pixels_and_its_own_screenshot(self):
        for field, values in (("panel_pixels", (None, True, 0, 999, "1200")),
                              ("opposing_panel_pixels", (None, True, -1, "40", 40.0)),
                              ("blue_panel_pixels", (None, True, 0, 499, "500", 500.0)),
                              ("yellow_panel_pixels", (None, True, 0, 499, "500", 500.0)),
                              ("panel_roi_pixels", (None, True, 0, -1, "5000", 5000.0)),
                              ("screenshot", (None, "missing.png", "CONTROL-ordinary-2.png"))):
            for value in values:
                with self.subTest(field=field, value=value):
                    reports = self.reports()
                    reports["client"]["cases"][0]["crossings"][0][field] = value
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports()
        self.write_reports()
        screenshot = self.results / "CONTROL-ordinary-0.png"
        screenshot.write_bytes(screenshot.read_bytes()[:24])
        with self.assertRaisesRegex(RuntimeError, "invalid screenshot"):
            verify.validate_ship_reports()

    def test_ship_panel_contrast_requires_bounded_counts_and_exact_coverage(self):
        for desired, opposing, area in ((1000, 50, 5000), (1001, 50, 5001),
                                         (2000, 100, 10000), (1000, 0, 2000)):
            with self.subTest(valid=(desired, opposing, area)):
                reports = self.reports()
                reports["client"]["cases"][0]["crossings"][0].update(
                    panel_pixels=desired, opposing_panel_pixels=opposing, panel_roi_pixels=area)
                self.write_reports(reports)
                verify.validate_ship_reports()
        for desired, opposing, area in ((1000, 51, 5000), (1000, 50, 5001),
                                         (1999, 0, 10000), (2000, 101, 10000),
                                         (1000, 0, 999), (1000, 5001, 5000),
                                         (1000, 1, 2000)):
            for case in range(4):
                for leg in range(4):
                    with self.subTest(invalid=(desired, opposing, area), case=case, leg=leg):
                        reports = self.reports()
                        reports["client"]["cases"][case]["crossings"][leg].update(
                            panel_pixels=desired, opposing_panel_pixels=opposing, panel_roi_pixels=area)
                        self.write_reports(reports)
                        with self.assertRaisesRegex(RuntimeError, "sampled area|coverage or state contrast"):
                            verify.validate_ship_reports()

    def test_ship_panel_requires_both_distinctive_colors_in_every_arrival(self):
        for blue, yellow, valid in ((501, 501, True), (500, 501, False),
                                    (501, 500, False), (10002, 501, False),
                                    (501, 10002, False), (4000, 4001, False)):
            for case in range(4):
                for leg in range(4):
                    with self.subTest(blue=blue, yellow=yellow, case=case, leg=leg):
                        reports = self.reports()
                        reports["client"]["cases"][case]["crossings"][leg].update(
                            panel_pixels=2001, opposing_panel_pixels=0, panel_roi_pixels=10001,
                            blue_panel_pixels=blue, yellow_panel_pixels=yellow)
                        self.write_reports(reports)
                        if valid:
                            verify.validate_ship_reports()
                        else:
                            with self.assertRaisesRegex(RuntimeError, "sampled area|coverage or state contrast"):
                                verify.validate_ship_reports()

    def test_normal_preparation_clears_ambient_ship_opt_in(self):
        root = self.results / "checkout"
        with patch.multiple(verify, ROOT=root, RESULT_DIR=root / "results",
                            SERVER_DIR=root / "server", CLIENT_DIR=root / "client"), \
             patch.dict(verify.os.environ, IP_SABLE_SHIPS="true", IP_SABLE_SHIPS_AERONAUTICS="true", IP_SABLE_SHIPS_SIZE="large"):
            env = verify.prepare_e2e()
        self.assertEqual(env["IP_SABLE_SHIPS"], "false")
        self.assertEqual(env["IP_SABLE_SHIPS_AERONAUTICS"], "false")
        self.assertEqual(env["IP_SABLE_SHIPS_SIZE"], "all")
        self.assertEqual(env["IP_SABLE_E2E_SERVER_DIR"], str((root / "server").resolve()))
        self.assertEqual(env["IP_SABLE_E2E_CLIENT_DIR"], str((root / "client").resolve()))
        self.assertEqual(env["IP_SABLE_E2E"], "true")

    def test_ship_only_view_distance_changes_both_sides_without_expanding_other_budgets(self):
        root = self.results / "checkout"
        configs = []
        with patch.multiple(verify, ROOT=root, RESULT_DIR=root / "results",
                            SERVER_DIR=root / "server", CLIENT_DIR=root / "client"), \
             patch.object(verify, "choose_tcp_udp_port", return_value=25566), \
             patch.dict(verify.os.environ, IP_SABLE_SHIPS="true", IP_SABLE_SHIPS_AERONAUTICS="true"):
            for ships in (False, True, False):
                verify.prepare_e2e(ships=ships)
                options = dict(line.split(":", 1) for line in (root / "client/options.txt").read_text().splitlines())
                properties = dict(line.split("=", 1) for line in (root / "server/server.properties").read_text().splitlines())
                configs.append((options, properties))
                self.assertEqual(options["renderDistance"], "10" if ships else "3")
                if ships:
                    self.assertEqual(options["renderClouds"], "false")
                else:
                    self.assertNotIn("renderClouds", options)
                self.assertEqual(properties["view-distance"], "10" if ships else "3")
                self.assertEqual(options["simulationDistance"], "3")
                self.assertEqual(properties["simulation-distance"], "3")
                self.assertEqual((options["overrideWidth"], options["overrideHeight"], options["maxFps"]), ("854", "480", "60"))
        self.assertEqual(configs[1][0], dict(configs[0][0], renderDistance="10", renderClouds="false"))
        self.assertEqual(configs[1][1], dict(configs[0][1], **{"view-distance": "10"}))
        self.assertEqual(configs[2], configs[0], "Returning to ordinary E2E must restore its original graphics budget")

    def test_preparation_uses_explicit_mode_for_smoke_baseline_and_powered_ships(self):
        for options in ({}, {"smoke": True}, {"ships": True}, {"ships": True, "aeronautics": True}):
            with self.subTest(options=options), patch.object(verify, "prepare_e2e", side_effect=RuntimeError("prepared")) as prepare:
                with self.assertRaisesRegex(RuntimeError, "prepared"):
                    verify.run_e2e(**options)
                prepare.assert_called_once_with(ships=options.get("ships", False))

    def test_ship_launch_passes_mode_to_both_processes_and_uses_twenty_minutes(self):
        for ships, ship_size in ((False, "all"), (True, "all"), (True, "control"), (True, "large")):
            self.write_reports()
            server, client = Mock(), Mock()
            with patch.object(verify, "prepare_e2e", return_value={"IP_SABLE_E2E_PORT": "25566"}) as prepare, \
                 patch.object(verify, "invalidate_generated_run_classpath"), \
                 patch.object(verify.subprocess, "Popen", return_value=server) as launch_server, \
                 patch.object(verify, "wait_for_server"), patch.object(verify, "wait_for_client") as wait, \
                 patch.object(verify, "launch_graphical_client", return_value=(client, None)) as launch_client, \
                 patch.object(verify, "stop_process_tree"), \
                 patch.dict(verify.os.environ, DISPLAY=":99", IP_NETWORK_STRESS="false"):
                verify.run_e2e(ships=ships, ship_size=ship_size)
            prepare.assert_called_once_with(ships=ships)
            self.assertEqual(launch_server.call_args.kwargs["env"]["IP_SABLE_SHIPS"], str(ships).lower())
            self.assertEqual(launch_client.call_args.args[1]["IP_SABLE_SHIPS"], str(ships).lower())
            self.assertEqual(launch_server.call_args.kwargs["env"]["IP_SABLE_SHIPS_SIZE"], ship_size)
            self.assertEqual(launch_client.call_args.args[1]["IP_SABLE_SHIPS_SIZE"], ship_size)
            self.assertIn("runSableE2EServer", launch_server.call_args.args[0])
            self.assertIn("runSableE2EClient", launch_client.call_args.args[0])
            wait.assert_called_once_with(server, client, timeout=1200 if ships else 300, smoke=False, ships=ships,
                                         aeronautics=False, ship_size=ship_size, standing_balloon=False)

    def test_windows_bridge_forwards_explicit_ship_mode(self):
        with patch.object(verify.ctypes, "windll", Mock(), create=True):
            verify.launch_windows_interactive(["gradlew.bat", "runSableE2EClient"],
                                              {"IP_SABLE_E2E": "true", "IP_SABLE_SHIPS": "true", "IP_SABLE_SHIPS_AERONAUTICS": "true",
                                               "IP_SABLE_SHIPS_SIZE": "large", "IP_SABLE_E2E_SERVER_DIR": "server-path",
                                               "IP_SABLE_E2E_CLIENT_DIR": "client-path"}, 7)
        wrapper = (self.results / "client-session.cmd").read_text()
        self.assertIn('set "IP_SABLE_SHIPS=true"', wrapper)
        self.assertIn('set "IP_SABLE_SHIPS_AERONAUTICS=true"', wrapper)
        self.assertIn('set "IP_SABLE_SHIPS_SIZE=large"', wrapper)
        self.assertIn('set "IP_SABLE_E2E_SERVER_DIR=server-path"', wrapper)
        self.assertIn('set "IP_SABLE_E2E_CLIENT_DIR=client-path"', wrapper)

    def test_selected_ship_size_requires_exact_subset_without_relaxing_other_evidence(self):
        for addon in (False, True):
            for size in ("control", "large"):
                with self.subTest(addon=addon, size=size):
                    reports = self.reports(aeronautics=addon, ship_size=size)
                    self.write_reports(reports)
                    verify.validate_ship_reports(aeronautics=addon, ship_size=size)
                    with self.assertRaisesRegex(RuntimeError, "expected .* ship cases"):
                        verify.validate_ship_reports(aeronautics=addon)
                    with self.assertRaisesRegex(RuntimeError, "unexpected or duplicate"):
                        verify.validate_ship_reports(aeronautics=addon, ship_size="large" if size == "control" else "control")
                    reports["server"]["cases"][0]["crossings"][0]["integrity"] = False
                    self.write_reports(reports)
                    with self.assertRaisesRegex(RuntimeError, "integrity"):
                        verify.validate_ship_reports(aeronautics=addon, ship_size=size)
                    self.write_reports(self.reports(aeronautics=addon))
                    with self.assertRaisesRegex(RuntimeError, "expected .* ship cases"):
                        verify.validate_ship_reports(aeronautics=addon, ship_size=size)
        with self.assertRaises(ValueError):
            verify.validate_ship_reports(ship_size="typo")

    def test_size_runs_have_fresh_separate_worlds_results_and_restore_runtime_paths(self):
        previous = verify.RESULT_DIR, verify.SERVER_DIR, verify.CLIENT_DIR
        protected = self.results / "protected-evidence.txt"
        protected.write_text("keep")
        for addon in (False, True):
            for size in ("control", "large"):
                name = "sable-ship-flight" + ("-aeronautics" if addon else "") + "-" + size
                results = self.results / "build" / (name + "-e2e")
                server = self.results / ("run-" + name + "-server")
                client = self.results / ("run-" + name + "-client")
                for directory in (results, server / "world", client / "logs"):
                    directory.mkdir(parents=True)
                    (directory / "old.txt").write_text("stale")
                def run(**kwargs):
                    self.assertEqual(kwargs, dict(ships=True, aeronautics=addon, ship_size=size))
                    self.assertEqual((verify.RESULT_DIR, verify.SERVER_DIR, verify.CLIENT_DIR), (results, server, client))
                    env = verify.prepare_e2e()
                    self.assertEqual(env["IP_SABLE_E2E_SERVER_DIR"], str(server.resolve()))
                    self.assertEqual(env["IP_SABLE_E2E_CLIENT_DIR"], str(client.resolve()))
                    self.assertFalse((server / "world").exists())
                    self.assertFalse((client / "logs").exists())
                    self.assertEqual(list(results.iterdir()), [])
                    (results / "this-run.txt").write_text(size)
                with patch.object(verify, "ROOT", self.results), patch.object(verify, "run_e2e", side_effect=run):
                    verify.run_ships(aeronautics=addon, ship_size=size)
                self.assertEqual((verify.RESULT_DIR, verify.SERVER_DIR, verify.CLIENT_DIR), previous)
        self.assertEqual(protected.read_text(), "keep")
        self.assertEqual(len(list(self.results.glob("build/sable-ship-flight*-e2e/this-run.txt"))), 4)

    def test_later_large_success_never_overwrites_failed_control_summary(self):
        for mode in ("ships", "ships-aeronautics"):
            for size in ("control", "large"):
                with patch.object(verify, "ROOT", self.results), \
                     patch.object(verify.sys, "argv", ["verify.py", mode, "--ship-size", size]), \
                     patch.object(verify, "run_ships", side_effect=RuntimeError("control failed") if size == "control" else None):
                    self.assertEqual(verify.main(), 1 if size == "control" else 0)
            for size in ("control", "large"):
                report = json.loads((self.results / "build" / f"verification-{mode}-{size}.json").read_text())
                self.assertEqual(report["stages"][0]["status"], "failed" if size == "control" else "passed")
                self.assertEqual(report["stages"][0]["name"], f"{mode}-{size}")

    def test_ship_size_cli_rejects_unrelated_modes_and_bad_sizes(self):
        for args in (("e2e", "--ship-size", "large"), ("ships", "--ship-size", "typo")):
            with patch.object(verify.sys, "argv", ["verify.py", *args]), patch.object(verify.sys, "stderr"):
                with self.assertRaises(SystemExit) as error:
                    verify.main()
                self.assertEqual(error.exception.code, 2)

    def test_ship_results_are_fresh_separate_and_paths_restore_after_failure(self):
        root = self.results / "checkout"
        old = root / "build/sable-dimension-stack-e2e/server-pass.txt"
        old.parent.mkdir(parents=True)
        old.write_text("established lane evidence")
        fresh = root / "build/sable-ship-flight-e2e"
        fresh.mkdir()
        (fresh / "client-ships.json").write_text("stale evidence")
        previous = verify.RESULT_DIR, verify.SERVER_LOG, verify.CLIENT_LOG
        def run(**kwargs):
            self.assertEqual(kwargs, {"ships": True, "aeronautics": False, "ship_size": "all"})
            self.assertEqual(verify.RESULT_DIR, fresh)
            env = verify.prepare_e2e()
            self.assertEqual(env["IP_SABLE_E2E_RESULT_DIR"], str(fresh.resolve()))
            self.assertEqual(list(fresh.iterdir()), [])
            raise RuntimeError("flight failed")
        with patch.multiple(verify, ROOT=root, SERVER_DIR=root / "server", CLIENT_DIR=root / "client"), \
             patch.object(verify, "run_e2e", side_effect=run):
            with self.assertRaisesRegex(RuntimeError, "flight failed"):
                verify.run_ships()
        self.assertEqual((verify.RESULT_DIR, verify.SERVER_LOG, verify.CLIENT_LOG), previous)
        self.assertEqual(old.read_text(), "established lane evidence")

    def test_ships_cli_records_draft_stage_and_full_keeps_existing_stages(self):
        for mode, expected in (("ships", ["ships"]), ("ships-aeronautics", ["ships-aeronautics"]),
                               ("full", ["core", "e2e", "visual"])):
            with patch.object(verify, "ROOT", self.results), patch.object(verify.sys, "argv", ["verify.py", mode]), \
                 patch.object(verify, "run_core"), patch.object(verify, "run_e2e"), patch.object(verify, "run_visual"), \
                 patch.object(verify, "run_ships") as ships:
                self.assertEqual(verify.main(), 0)
            report = json.loads((self.results / "build" / f"verification-{mode}.json").read_text())
            self.assertEqual([stage["name"] for stage in report["stages"]], expected)
            self.assertTrue(all(stage["status"] == "passed" for stage in report["stages"]))
            self.assertEqual(ships.call_count, int(mode.startswith("ships")))
            if mode == "ships-aeronautics":
                ships.assert_called_once_with(aeronautics=True, ship_size="all")

    def test_aeronautics_requires_independent_exact_runtime_receipts(self):
        self.write_reports(self.reports(aeronautics=True))
        verify.validate_results(0, ships=True, aeronautics=True)
        for side in ("server", "client"):
            for mods in (None, {}, "loaded", {"aeronautics": "1.3.1"},
                         {"aeronautics": "1.3.2", "simulated": "1.3.1", "offroad": "1.3.1"}):
                with self.subTest(side=side, mods=mods):
                    reports = self.reports(aeronautics=True)
                    reports[side]["addons"] = mods
                    self.write_reports(reports)
                    with self.assertRaisesRegex(RuntimeError, "runtime versions"):
                        verify.validate_results(0, ships=True, aeronautics=True)
        self.write_reports()
        with self.assertRaisesRegex(RuntimeError, "runtime versions"):
            verify.validate_results(0, ships=True, aeronautics=True)
        with self.assertRaisesRegex(ValueError, "requires ship mode"):
            verify.validate_results(0, aeronautics=True)

    def test_aeronautics_requires_powered_travel_without_native_velocity_writes(self):
        for field, values in (("powered_thrust", (None, False, 1, "true")),
                              ("no_native_velocity_writes", (None, False, 1, "true")),
                              ("propeller_count", (None, 0, -1, True, 2.0, 7, 9, 251, 253)),
                              ("force_samples", (None, 0, 4, True, 5.0)),
                              ("gravity_y", (None, 0, 10, True, "-10", float("nan"), float("inf"))),
                              ("peak_vertical_acceleration", (None, 0, True, "0.5", float("nan"), float("inf")))):
            for value in values:
                for case in range(2):
                    for leg in range(4):
                        with self.subTest(field=field, value=value, case=case, leg=leg):
                            reports = self.reports(aeronautics=True)
                            reports["server"]["cases"][case]["crossings"][leg][field] = value
                            self.write_reports(reports)
                            with self.assertRaises(RuntimeError):
                                verify.validate_ship_reports(aeronautics=True)
        for leg in range(4):
            reports = self.reports(aeronautics=True)
            reports["server"]["cases"][0]["crossings"][leg]["peak_vertical_acceleration"] *= -1
            self.write_reports(reports)
            with self.assertRaisesRegex(RuntimeError, "directional powered acceleration"):
                verify.validate_ship_reports(aeronautics=True)
        for value in (None, False, 1, "true"):
            reports = self.reports(aeronautics=True)
            reports["client"]["cases"][0]["crossings"][0]["propellers"] = value
            self.write_reports(reports)
            with self.assertRaisesRegex(RuntimeError, "propellers"):
                verify.validate_ship_reports(aeronautics=True)

    def test_aeronautics_requires_two_powered_ordinary_cases_without_changing_baseline_matrix(self):
        powered = self.reports(aeronautics=True)
        self.assertEqual([(case["size"], case["route"]) for case in powered["server"]["cases"]],
                         [("CONTROL", "ordinary"), ("LARGE", "ordinary")])
        self.write_reports(powered)
        verify.validate_ship_reports(aeronautics=True)
        with self.assertRaisesRegex(RuntimeError, "expected 6 ship cases"):
            verify.validate_ship_reports()
        self.write_reports()
        verify.validate_ship_reports()
        for side in ("server", "client"):
            for index in range(2):
                reports = self.reports(aeronautics=True)
                reports[side]["cases"][index]["route"] = "dimension-stack"
                self.write_reports(reports)
                with self.assertRaisesRegex(RuntimeError, "unexpected or duplicate case"):
                    verify.validate_ship_reports(aeronautics=True)
            reports = self.reports(aeronautics=True)
            reports[side]["cases"] += reports[side]["cases"]
            self.write_reports(reports)
            with self.assertRaisesRegex(RuntimeError, "expected 2 ship cases"):
                verify.validate_ship_reports(aeronautics=True)

    def test_aeronautics_requires_measured_powered_motion_in_every_leg(self):
        for field, values in (("ticks", (None, True, 0, 4, 5.0)),
                              ("verticalDisplacement", (None, True, 0, "2", float("nan"), float("inf"))),
                              ("verticalVelocityChange", (None, True, 0, "4", float("nan"), float("inf"))),
                              ("startVerticalVelocity", (None, True, "0", float("nan"), float("inf"))),
                              ("endVerticalVelocity", (None, True, "4", float("nan"), float("inf"))),
                              ("dimension", (None, "minecraft:the_end")),
                              ("againstGravity", (None, 0, 1, "false"))):
            for value in values:
                for case in range(2):
                    for leg in range(4):
                        with self.subTest(field=field, value=value, case=case, leg=leg):
                            reports = self.reports(aeronautics=True)
                            reports["server"]["cases"][case]["crossings"][leg]["powered_motion"][field] = value
                            self.write_reports(reports)
                            with self.assertRaises(RuntimeError):
                                verify.validate_ship_reports(aeronautics=True)
        for value in (None, {}, True, []):
            reports = self.reports(aeronautics=True)
            reports["server"]["cases"][0]["crossings"][0]["powered_motion"] = value
            self.write_reports(reports)
            with self.assertRaisesRegex(RuntimeError, "measured powered motion interval"):
                verify.validate_ship_reports(aeronautics=True)
        for leg in range(4):
            direction = -1 if leg % 2 == 0 else 1
            for field, value in (("verticalDisplacement", direction * 0.05),
                                  ("verticalVelocityChange", direction * 0.05),
                                  ("verticalDisplacement", -direction * 2),
                                  ("verticalVelocityChange", -direction * 4),
                                  ("startVerticalVelocity", 10.0),
                                  ("endVerticalVelocity", direction * 8.0),
                                  ("againstGravity", direction < 0)):
                with self.subTest(leg=leg, field=field, value=value):
                    reports = self.reports(aeronautics=True)
                    reports["server"]["cases"][0]["crossings"][leg]["powered_motion"][field] = value
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports(aeronautics=True)

    def test_aeronautics_rejects_gravity_only_descent_and_unaccelerated_ascent(self):
        for leg, acceleration, valid in ((0, -10.0, False), (2, -11.0, False), (0, -11.01, True),
                                         (1, 0.05, False), (3, 0.049, False), (1, 0.051, True)):
            with self.subTest(leg=leg, acceleration=acceleration):
                reports = self.reports(aeronautics=True)
                reports["server"]["cases"][0]["crossings"][leg]["peak_vertical_acceleration"] = acceleration
                self.write_reports(reports)
                if valid:
                    verify.validate_ship_reports(aeronautics=True)
                else:
                    with self.assertRaisesRegex(RuntimeError, "beyond gravity"):
                        verify.validate_ship_reports(aeronautics=True)

    def test_aeronautics_lift_budget_requires_finite_native_measurements(self):
        fields = ("mass", "gravityY", "weight", "maximumStaticLift", "staticLiftWeightRatio",
                  "minimumAirPressure", "trimMomentX", "trimMomentZ")
        for field in fields:
            for value in (None, True, "1.5", float("nan"), float("inf")):
                for case in range(2):
                    with self.subTest(field=field, value=value, case=case):
                        reports = self.reports(aeronautics=True)
                        reports["server"]["cases"][case]["lift_budget"][field] = value
                        self.write_reports(reports)
                        with self.assertRaisesRegex(RuntimeError, "invalid lift budget"):
                            verify.validate_ship_reports(aeronautics=True)
        for value in (None, {}, True, []):
            reports = self.reports(aeronautics=True)
            reports["server"]["cases"][0]["lift_budget"] = value
            self.write_reports(reports)
            with self.assertRaisesRegex(RuntimeError, "lift budget"):
                verify.validate_ship_reports(aeronautics=True)

    def test_aeronautics_lift_budget_checks_mass_lift_atmosphere_engines_and_trim(self):
        invalid = (("mass", 0), ("mass", -1), ("mass", 500), ("gravityY", 0), ("gravityY", 10),
                   ("weight", -1), ("weight", 5000), ("maximumStaticLift", 0),
                   ("maximumStaticLift", 11000), ("staticLiftWeightRatio", 1.1),
                   ("staticLiftWeightRatio", 2.0), ("minimumAirPressure", 0),
                   ("minimumAirPressure", -1), ("atmosphereModified", True),
                   ("atmosphereModified", None), ("atmosphereModified", 0),
                   ("engineCount", None), ("engineCount", True), ("engineCount", 8.0),
                   ("engineCount", 7), ("engineCount", 251), ("engineCount", 253),
                   ("trimMomentX", 300), ("trimMomentZ", -300))
        for field, value in invalid:
            for case in range(2):
                with self.subTest(field=field, value=value, case=case):
                    reports = self.reports(aeronautics=True)
                    reports["server"]["cases"][case]["lift_budget"][field] = value
                    self.write_reports(reports)
                    with self.assertRaises(RuntimeError):
                        verify.validate_ship_reports(aeronautics=True)
        for moment, valid in ((299.9, True), (300.0, False)):
            reports = self.reports(aeronautics=True)
            reports["server"]["cases"][0]["lift_budget"].update(trimMomentX=moment, trimMomentZ=0.0)
            self.write_reports(reports)
            if valid:
                verify.validate_ship_reports(aeronautics=True)
            else:
                with self.assertRaisesRegex(RuntimeError, "unbalanced static thrust moment"):
                    verify.validate_ship_reports(aeronautics=True)

    def test_aeronautics_stages_both_processes_and_cleans_after_they_stop(self):
        self.write_reports(self.reports(aeronautics=True))
        events = []
        @contextmanager
        def stage(root, results, runs):
            self.assertEqual(results, self.results)
            self.assertEqual(runs, (verify.SERVER_DIR, verify.CLIENT_DIR))
            events.append("stage")
            try:
                yield {}
            finally:
                events.append("cleanup")
        server, client = Mock(), Mock()
        with patch.object(verify, "prepare_e2e", return_value={"IP_SABLE_E2E_PORT": "25566"}), \
             patch.object(verify, "invalidate_generated_run_classpath"), \
             patch.object(verify.subprocess, "Popen", side_effect=lambda *a, **k: events.append("server") or server) as launch, \
             patch.object(verify, "wait_for_server"), patch.object(verify, "wait_for_client") as wait, \
             patch.object(verify, "launch_graphical_client", return_value=(client, None)) as launch_client, \
             patch.object(verify, "stop_process_tree", side_effect=lambda proc: events.append("stop")), \
             patch.object(verify.aeronautics_bundle, "stage", side_effect=stage), \
             patch.dict(verify.os.environ, DISPLAY=":99", IP_NETWORK_STRESS="false"):
            verify.run_e2e(ships=True, aeronautics=True)
        self.assertEqual(events, ["stage", "server", "stop", "stop", "cleanup"])
        self.assertEqual(launch.call_args.kwargs["env"]["IP_SABLE_SHIPS_AERONAUTICS"], "true")
        self.assertEqual(launch_client.call_args.args[1]["IP_SABLE_SHIPS_AERONAUTICS"], "true")
        wait.assert_called_once_with(server, client, timeout=1200, smoke=False, ships=True, aeronautics=True, ship_size="all", standing_balloon=False)

    def test_aeronautics_results_never_overwrite_baseline_evidence(self):
        root = self.results / "checkout"
        baseline = root / "build/sable-ship-flight-e2e/server-pass.txt"
        baseline.parent.mkdir(parents=True)
        baseline.write_text("baseline evidence")
        def run(**kwargs):
            self.assertEqual(kwargs, {"ships": True, "aeronautics": True, "ship_size": "all"})
            self.assertEqual(verify.RESULT_DIR, root / "build/sable-ship-flight-aeronautics-e2e")
        with patch.object(verify, "ROOT", root), patch.object(verify, "run_e2e", side_effect=run):
            verify.run_ships(aeronautics=True)
        self.assertEqual(baseline.read_text(), "baseline evidence")

    def test_ship_ci_is_additive_preserves_both_transports_and_failure_artifacts(self):
        workflow = (verify.ROOT / ".github/workflows/ci.yml").read_text()
        ships = workflow.split("  sable-ship-flight:\n", 1)[1].split("  graphical-e2e:\n", 1)[0]
        for transport in ("udp", "tcp"):
            for size in ("control", "large"):
                self.assertIn(f"{{ transport: {transport}, mode: ships, ship_size: {size} }}", ships)
        for size in ("control", "large"):
            self.assertIn(f"{{ transport: udp, mode: ships-aeronautics, ship_size: {size} }}", ships)
        self.assertIn("fail-fast: false", ships)
        self.assertNotIn("continue-on-error", ships)
        self.assertIn("timeout-minutes: 45", ships)
        self.assertIn('python3 tools/verify.py "${{ matrix.mode }}"', ships)
        self.assertIn("IP_VERIFICATION_NETWORKING: bundled", ships)
        self.assertIn("if: always()", ships)
        for artifact in ("build/verification-ships*.json", "build/sable-ship-flight*-e2e/**",
                         "${{ env.SHIP_RUNTIME_PREFIX }}-server/server.properties", "${{ env.SHIP_RUNTIME_PREFIX }}-client/options.txt",
                         "${{ env.SHIP_RUNTIME_PREFIX }}-server/crash-reports/**", "${{ env.SHIP_RUNTIME_PREFIX }}-client/logs/**"):
            self.assertIn(artifact, ships)
        for value in ("true", "false"):
            self.assertIn(f"disable_udp_pipeline = {value}", ships)
            self.assertGreaterEqual(ships.count(f"attempt_udp_networking = {value}"), 2)
        required = workflow.split("  required:\n", 1)[1]
        self.assertIn("needs: [changes, core, graphical-e2e, sable-ship-flight, portal-visual, real-pack-visual, standing-balloon]", required)
        self.assertIn("SHIPS: ${{ needs.sable-ship-flight.result }}", required)
        self.assertIn("if: needs.changes.outputs.e2e == 'true'", ships)



if __name__ == "__main__":
    unittest.main()
