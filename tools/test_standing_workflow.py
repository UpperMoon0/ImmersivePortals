"""Exercise the shared action's shell steps without starting Minecraft."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import verify


class StandingWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.action = (verify.ROOT / ".github/actions/standing-balloon/action.yml").read_text()
        cls.bash = shutil.which("bash")
        if os.name == "nt" and (git := shutil.which("git")):
            cls.bash = str(Path(git).resolve().parents[1] / "bin/bash.exe")
        if not cls.bash or not Path(cls.bash).is_file():
            raise unittest.SkipTest("Shared action execution requires Bash")

    def script(self, step):
        block = self.action.split(f"    - name: {step}\n", 1)[1].split("    - name:", 1)[0]
        return "\n".join(line[8:] for line in block.split("      run: |\n", 1)[1].splitlines())

    def test_ci_and_release_share_action_with_their_original_options(self):
        for workflow, cold in (("ci", "true"), ("release", "false")):
            text = (verify.ROOT / f".github/workflows/{workflow}.yml").read_text()
            job = text.split("  standing-balloon:\n", 1)[1].split("  sable-ship-flight:\n", 1)[0]
            action_path = "./.github/actions/standing-balloon" if workflow == "ci" else "./.verification-policy/.github/actions/standing-balloon"
            self.assertIn(f"uses: {action_path}", job)
            self.assertIn("transport: ${{ matrix.transport }}", job)
            self.assertIn(f"cold-start: '{cold}'", job)
            self.assertLess(job.index("uses: actions/checkout@v6"), job.index(f"uses: {action_path}"))
            if workflow == "release":
                self.assertIn("ref: ${{ inputs.tag && format('refs/tags/{0}', inputs.tag) || github.ref }}", job)
                policy = job.split("      - name: Checkout standing verification action from the executing workflow\n", 1)[1]
                self.assertIn("ref: ${{ github.workflow_sha }}", policy)
                self.assertIn("path: .verification-policy", policy)
                self.assertIn("sparse-checkout: .github/actions/standing-balloon", policy)
        self.assertIn("if: always()", self.action)
        self.assertIn("name: standing-balloon-${{ inputs.transport }}", self.action)
        self.assertNotIn("continue-on-error", self.action)
        for artifact in ("build/verification-standing-balloon.json", "build/sable-dimension-stack-e2e/**",
                         "run-sable-e2e-server/logs/**", "run-sable-e2e-client/logs/**",
                         "run-sable-e2e-server/config/sable-common.toml",
                         "run-sable-e2e-client/config/sable-client.toml"):
            self.assertIn(artifact, self.action)

    def test_transport_step_writes_both_native_configs_and_rejects_unknown_transport(self):
        for transport in ("tcp", "udp", "unknown"):
            with self.subTest(transport=transport), tempfile.TemporaryDirectory() as directory:
                result = subprocess.run([self.bash, "-e", "-c", self.script("Configure Sable transport")],
                                        cwd=directory, env=dict(os.environ, TRANSPORT=transport), capture_output=True)
                if transport == "unknown":
                    self.assertNotEqual(result.returncode, 0)
                    continue
                self.assertEqual(result.returncode, 0, result.stderr)
                enabled = str(transport == "udp").lower()
                disabled = str(transport == "tcp").lower()
                root = Path(directory)
                for side in ("server", "client"):
                    self.assertEqual((root / f"run-sable-e2e-{side}/config/sable-common.toml").read_text(),
                                     f"disable_udp_pipeline = {disabled}\nattempt_udp_networking = {enabled}\n")
                self.assertEqual((root / "run-sable-e2e-client/config/sable-client.toml").read_text(),
                                 f"attempt_udp_networking = {enabled}\n")

    def test_flight_step_passes_cold_flag_and_propagates_verifier_failure(self):
        for cold in ("true", "false"):
            for exit_code in (0, 7):
                with self.subTest(cold=cold, exit_code=exit_code), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    (root / "gradlew").touch()
                    stub = 'python3() { printf "%s\\n" "$@" > args.txt; return "$VERIFY_EXIT"; }\n'
                    result = subprocess.run([self.bash, "-e", "-c", stub + self.script("Unmounted envelope and deck burner ascents")],
                                            cwd=root, env=dict(os.environ, STANDING_COLD_START=cold,
                                                               VERIFY_EXIT=str(exit_code)), capture_output=True)
                    self.assertEqual(result.returncode, exit_code, result.stderr)
                    self.assertEqual((root / "args.txt").read_text().splitlines(),
                                     ["tools/verify.py", "standing-balloon"] + (["--standing-cold-start"] if cold == "true" else []))
