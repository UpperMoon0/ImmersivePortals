"""Fast regression tests for false passes and failed-process supervision."""
import tempfile
import json
import socket
import subprocess
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import verify


class VerificationHarnessTest(unittest.TestCase):
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

    def test_ci_transport_matrix_controls_common_and_client_udp_flags(self):
        workflow = (verify.ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
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
        scenes = {"solid-visible", "solid-clipped", "nested", "create-nested", "mirror", "create-visible", "create-clipped", "create-crumbling-clean", "create-crumbling-damaged", "create-crumbling-restored", "create-crumbling-clipped"}
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


if __name__ == "__main__":
    unittest.main()
