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
        scenes = {"solid-visible", "solid-clipped", "nested", "create-nested", "mirror", "create-visible", "create-clipped", "create-crumbling-clean", "create-crumbling-damaged", "create-crumbling-clipped"}
        phases = ["before-reload", "after-reload"]
        if active:
            phases.append("after-toggle")
            scenes.update(f"{program}-{side}" for program in ("cutout", "translucent", "entity", "block-entity", "particle")
                          for side in ("visible", "clipped"))
        checks = [{"phase": phase, "scene": scene, "screenshot": "test.png",
                   "shaders_active": active, "pack": verify.FIXTURE_NAME if active else "",
                   "width": 854 if phase == "before-reload" else 960, "height": 480 if phase == "before-reload" else 640,
                   "flywheel": {"nestedContextsRestored": 1, "contextAccessorsInstalled": True, "contextClearedAfterFrame": True}, "source_motion_changed_pixels": 100, "create_motion_changed_pixels": 100,
                   "create_server_motion": True, "nested_contexts_restored_this_scene": 1, "crumbling_changed_pixels": 100, "crumbling_darkening": 10,
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
            with zipfile.ZipFile(jar, "a") as archive:
                archive.writestr("qouteall/imm_ptl/core/gametest/sablee2e/PortalSmokeClient.class", b"class")
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
        self.assertGreater(active, base)
        self.assertGreater(active, real_pack)
        self.assertGreater(verify.visual_timeout("iris-active", 1200, "normal", True), active)

    def test_live_shader_options_must_match_recorded_preset(self):
        (self.results / "fixture.json").write_text(json.dumps({"name": verify.FIXTURE_NAME, "options": {"SHADOWS": "false"}}))
        self.write_shader_evidence(self.shader_evidence())
        with self.assertRaisesRegex(RuntimeError, "live shaderpack options"):
            verify.validate_shader_evidence("iris-active")

    def test_create_evidence_requires_separate_source_target_motion_and_crumbling(self):
        for scene, key, value in (("create-visible", "source_motion_changed_pixels", 0),
                                  ("create-visible", "create_motion_changed_pixels", 0),
                                  ("create-visible", "create_server_motion", False),
                                  ("create-crumbling-damaged", "crumbling_changed_pixels", 0),
                                  ("create-crumbling-damaged", "crumbling_darkening", 0),
                                  ("create-nested", "nested_contexts_restored_this_scene", 0)):
            with self.subTest(scene=scene, key=key):
                report = self.shader_evidence()
                next(check for check in report["checks"] if check["scene"] == scene)[key] = value
                self.write_shader_evidence(report)
                with self.assertRaises(RuntimeError):
                    verify.validate_shader_evidence("iris-active")

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
