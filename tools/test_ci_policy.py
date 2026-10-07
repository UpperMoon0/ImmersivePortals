import unittest
import itertools
import os
from pathlib import Path
import subprocess
from ci_policy import needs_e2e


class CIPolicyTest(unittest.TestCase):
    def test_documentation_only_skips_graphics(self):
        self.assertFalse(needs_e2e(["README.md", "changelog/6.0.9.md", "LICENSE"]))

    def test_runtime_build_harness_and_unknown_changes_require_graphics(self):
        for path in ("src/main/java/Portal.java", "src/main/resources/shader.json",
                     "build.gradle", "gradle.properties", "tools/verify.py",
                     ".github/workflows/ci.yml", "new-runtime-file"):
            with self.subTest(path=path):
                self.assertTrue(needs_e2e(["README.md", path]))

    def test_empty_change_list_fails_closed(self):
        self.assertTrue(needs_e2e([]))

    def test_required_workflow_gate_rejects_skipped_or_failed_real_packs(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml").read_text()
        gate = workflow.split("      - name: Enforce all selected checks\n", 1)[1].split("        run: |\n", 1)[1]
        script = "\n".join(line[10:] for line in gate.splitlines())
        statuses = ("success", "failure", "cancelled", "skipped")
        for required in ("true", "false", ""):
            expected_status = "success" if required == "true" else "skipped"
            for heavy, visual, real_packs in itertools.product(statuses, repeat=3):
                env = dict(os.environ, SELECT="success", CORE="success", REQUIRED=required,
                           HEAVY=heavy, VISUAL=visual, REAL_PACKS=real_packs)
                result = subprocess.run(["bash", "-e", "-c", script], env=env, capture_output=True)
                expected = required in ("true", "false") and all(
                    value == expected_status for value in (heavy, visual, real_packs))
                self.assertEqual(result.returncode == 0, expected, (required, heavy, visual, real_packs))
        for key in ("SELECT", "CORE"):
            env = dict(os.environ, SELECT="success", CORE="success", REQUIRED="true",
                       HEAVY="success", VISUAL="success", REAL_PACKS="success")
            env[key] = "failure"
            self.assertNotEqual(subprocess.run(["bash", "-e", "-c", script], env=env).returncode, 0)

    def test_real_packs_wait_for_fast_checks_but_remain_required(self):
        workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml").read_text()
        fast, slow = workflow.split("  real-pack-visual:\n", 1)
        slow, required = slow.split("  required:\n", 1)
        self.assertNotIn("real_pack: makeup", fast)
        self.assertIn("needs: [changes, core, graphical-e2e, portal-visual]", slow)
        self.assertNotIn("always()", slow)
        self.assertEqual(slow.count("real_pack: makeup"), 2)
        self.assertEqual(slow.count("real_pack: complementary"), 2)
        self.assertIn("needs: [changes, core, graphical-e2e, portal-visual, real-pack-visual]", required)
        self.assertIn("REAL_PACKS: ${{ needs.real-pack-visual.result }}", required)
