"""Regressions for coverage-preserving release evidence promotion."""
import copy
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release_evidence as evidence


class ReleaseEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.repo = "UpperMoon0/ImmersivePortals"
        self.commit = "a" * 40
        self.run = dict(id=123, run_attempt=2, status="completed", conclusion="success", event="push",
                        head_branch="main", head_sha=self.commit, path=".github/workflows/ci.yml",
                        repository={"full_name": self.repo}, head_repository={"full_name": self.repo})
        self.jobs = [dict(name=name, status="completed", conclusion="success", run_attempt=2,
                          head_sha=self.commit) for name in evidence.required_jobs()]
        self.artifacts = [dict(id=456, name=evidence.ARTIFACT, expired=False,
                               workflow_run={"id": 123, "head_sha": self.commit})]

    def validate(self):
        return evidence.validate_run(self.run, self.jobs, self.artifacts, self.repo, self.commit)

    def test_complete_exact_commit_main_ci_is_eligible(self):
        self.assertEqual(self.validate()["id"], 456)

    def test_ref_suffixed_main_ci_is_eligible_for_real_resolution(self):
        # Actions REST reports workflow_run.path with this suffix.
        self.run["path"] = ".github/workflows/ci.yml@main"
        self.assertEqual(self.validate()["id"], 456)
        with patch.object(evidence, "api", return_value=self.run), \
             patch.object(evidence, "pages", side_effect=[self.jobs, self.artifacts]):
            self.assertEqual(evidence.resolve(self.repo, self.commit, "123"),
                             dict(reuse="true", run_id="123", run_attempt="2", artifact_id="456"))

    def test_only_exact_main_ref_suffix_is_accepted(self):
        for path in (".github/workflows/ci.yml@feature",
                     ".github/workflows/ci.yml@refs/pull/23/merge",
                     ".github/workflows/ci.yml@main/extra",
                     ".github/workflows/ci.yml@main@feature",
                     ".github/workflows/other.yml@main",
                     ".github/workflows/ci.yml@"):
            with self.subTest(path=path):
                self.run["path"] = path
                with self.assertRaises(ValueError):
                    self.validate()

    def test_wrong_commit_pr_branch_workflow_repository_or_non_success_cannot_be_reused(self):
        cases = {"head_sha": "b" * 40, "event": "pull_request", "head_branch": "feature",
                 "path": ".github/workflows/nightly.yml", "status": "in_progress", "conclusion": "failure",
                 "repository": {"full_name": "attacker/repo"}, "head_repository": {"full_name": "attacker/repo"}}
        original = copy.deepcopy(self.run)
        for key, value in cases.items():
            with self.subTest(key=key):
                self.run = {**original, key: value}
                with self.assertRaises(ValueError):
                    self.validate()

    def test_every_single_missing_or_duplicate_check_rejects_reuse(self):
        complete = copy.deepcopy(self.jobs)
        for index, job in enumerate(complete):
            with self.subTest(name=job["name"]):
                self.jobs = complete[:index] + complete[index + 1:]
                with self.assertRaises(ValueError):
                    self.validate()
                self.jobs = complete + [job]
                with self.assertRaises(ValueError):
                    self.validate()

    def test_skipped_failed_cancelled_old_attempt_and_other_commit_checks_reject_reuse(self):
        for job in self.jobs:
            for key, value in (("conclusion", "skipped"), ("conclusion", "failure"),
                               ("conclusion", "cancelled"), ("status", "queued"),
                               ("run_attempt", 1), ("head_sha", "b" * 40)):
                with self.subTest(name=job["name"], key=key, value=value):
                    old = job[key]
                    job[key] = value
                    with self.assertRaises(ValueError):
                        self.validate()
                    job[key] = old

    def test_expired_missing_ambiguous_or_foreign_artifact_rejects_reuse(self):
        original = copy.deepcopy(self.artifacts[0])
        cases = [[], [original, original], [{**original, "expired": True}],
                 [{**original, "workflow_run": {"id": 999, "head_sha": self.commit}}],
                 [{**original, "workflow_run": {"id": 123, "head_sha": "b" * 40}}]]
        for artifacts in cases:
            with self.subTest(artifacts=artifacts):
                self.artifacts = artifacts
                with self.assertRaises(ValueError):
                    self.validate()

    def test_unavailable_or_ineligible_evidence_falls_back_to_full_verification(self):
        with patch.object(evidence, "api", side_effect=OSError("unavailable")):
            self.assertEqual(evidence.resolve(self.repo, self.commit, "123"), {"reuse": "false"})
        with patch.object(evidence, "api") as api:
            for run_id in ("", "-1", "123\nreuse=true", "0"):
                self.assertEqual(evidence.resolve(self.repo, self.commit, run_id), {"reuse": "false"})
            api.assert_not_called()
        with patch.object(evidence, "api", return_value=self.run), \
             patch.object(evidence, "pages", side_effect=[self.jobs, []]):
            self.assertEqual(evidence.resolve(self.repo, self.commit, "123"), {"reuse": "false"})

    def test_eligible_resolution_binds_run_attempt_and_artifact_id(self):
        with patch.object(evidence, "api", return_value=self.run), \
             patch.object(evidence, "pages", side_effect=[self.jobs, self.artifacts]) as pages:
            self.assertEqual(evidence.resolve(self.repo, self.commit, "123"),
                             dict(reuse="true", run_id="123", run_attempt="2", artifact_id="456"))
            self.assertIn("/attempts/2/jobs", pages.call_args_list[0].args[0])

    def test_api_pagination_does_not_drop_later_checks(self):
        with patch.object(evidence, "api", side_effect=[{"jobs": [1] * 100}, {"jobs": [2]}]) as api:
            self.assertEqual(evidence.pages("endpoint", "jobs"), [1] * 100 + [2])
            self.assertTrue(api.call_args_list[-1].args[0].endswith("page=2"))

    def test_bundle_checks_exact_jar_version_commit_run_attempt_and_checksum(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            jar = directory / "immersive_portals-6.0.12.jar"
            jar.write_bytes(b"tested release jar")
            manifest = evidence.write_manifest(directory, self.commit, 123, 2, "6.0.12")
            self.assertEqual(evidence.validate_bundle(directory, self.commit, 123, 2, "6.0.12"), jar)
            for key, value in (("commit", "b" * 40), ("run_id", 999), ("run_attempt", 1),
                               ("version", "6.0.11"), ("schema", 0), ("jar", "../evil.jar"),
                               ("sha256", "b" * 64)):
                with self.subTest(key=key):
                    (directory / evidence.MANIFEST).write_text(json.dumps({**manifest, key: value}))
                    with self.assertRaises(ValueError):
                        evidence.validate_bundle(directory, self.commit, 123, 2, "6.0.12")
            (directory / evidence.MANIFEST).write_text(json.dumps(manifest))
            jar.write_bytes(b"modified jar")
            with self.assertRaisesRegex(ValueError, "checksum"):
                evidence.validate_bundle(directory, self.commit, 123, 2, "6.0.12")
            jar.unlink()
            with self.assertRaises(FileNotFoundError):
                evidence.validate_bundle(directory, self.commit, 123, 2, "6.0.12")

    def test_coverage_contract_matches_every_ci_matrix_member(self):
        # CI's flow-map entries have simple scalars. Compare rendered job names
        # without adding a runtime YAML dependency to verification runners.
        workflow = (evidence.ROOT / ".github/workflows/ci.yml").read_text()
        expected = ["Select integration checks", "Core verification", "Required verification",
                    "Sable graphical E2E / udp", "Sable graphical E2E / tcp"]
        for group in ("portal-visual", "real-pack-visual"):
            block = re.split(r"\n  [a-z][\w-]*:\n", workflow.split(f"\n  {group}:\n", 1)[1], maxsplit=1)[0]
            for flow in re.findall(r"- \{ ([^}]+) \}", block):
                values = [pair.split(": ", 1)[1] for pair in flow.split(", ")]
                expected.append(f"{group} ({', '.join(values)}) / visual")
        self.assertEqual(len(expected), 35)
        self.assertCountEqual(evidence.required_jobs(), expected)

    def test_fresh_release_keeps_every_ci_visual_configuration(self):
        def members(text):
            return [tuple(sorted(pair.split(": ", 1) for pair in flow.split(", ")))
                    for flow in re.findall(r"- \{ ([^}]+) \}", text)]
        ci = (evidence.ROOT / ".github/workflows/ci.yml").read_text()
        release = (evidence.ROOT / ".github/workflows/release.yml").read_text()
        self.assertEqual(len(members(ci)), 30)
        self.assertCountEqual(members(ci), members(release))

    def test_release_gate_rejects_missing_skipped_failed_or_cancelled_coverage(self):
        bash = shutil.which("bash")
        if os.name == "nt":
            git = shutil.which("git")
            candidate = Path(git).resolve().parents[1] / "bin/bash.exe" if git else None
            if candidate and candidate.is_file():
                bash = str(candidate)
        if not bash or subprocess.run([bash, "-c", "exit 0"], capture_output=True).returncode:
            self.skipTest("A working Bash is required to execute the actual release gate")
        workflow = (evidence.ROOT / ".github/workflows/release.yml").read_text()
        block = workflow.split("      - name: Enforce reused or fresh coverage\n", 1)[1].split("\n  release:", 1)[0]
        script = "\n".join(line[10:] for line in block.split("        run: |\n", 1)[1].splitlines())
        for reuse in ("true", "false", ""):
            for values in itertools.product(("success", "skipped", "failure", "cancelled"), repeat=3):
                env = dict(os.environ, REUSE=reuse, EVIDENCE="success", **dict(zip(("CORE", "E2E", "VISUAL"), values)))
                result = subprocess.run([bash, "-e", "-c", script], env=env, capture_output=True)
                expected = reuse in ("true", "false") and all(v == ("skipped" if reuse == "true" else "success") for v in values)
                self.assertEqual(result.returncode == 0, expected, (reuse, values))
        env = dict(os.environ, EVIDENCE="failure", REUSE="true", CORE="skipped", E2E="skipped", VISUAL="skipped")
        self.assertNotEqual(subprocess.run([bash, "-e", "-c", script], env=env).returncode, 0)
        publish = workflow.split("\n  release:\n", 1)[1]
        self.assertIn("!cancelled() && needs.evidence.result == 'success' && needs.verified.result == 'success'", publish)

    def test_workflow_provenance_is_bound_to_core_artifact_and_source_run(self):
        ci = (evidence.ROOT / ".github/workflows/ci.yml").read_text()
        tag = (evidence.ROOT / ".github/workflows/tag-on-bump.yml").read_text()
        release = (evidence.ROOT / ".github/workflows/release.yml").read_text()
        self.assertIn("run: python3 tools/release_evidence.py record", ci)
        self.assertIn("if: github.event_name == 'push' && github.ref == 'refs/heads/main'", ci)
        self.assertIn(f"name: {evidence.ARTIFACT}", ci)
        self.assertIn("SOURCE_CI_RUN_ID: ${{ github.event.workflow_run.id }}", tag)
        self.assertIn('-f source_ci_run_id="$SOURCE_CI_RUN_ID"', tag)
        self.assertIn("artifact-ids: ${{ needs.evidence.outputs.artifact_id }}", release)
        self.assertIn("run-id: ${{ needs.evidence.outputs.run_id }}", release)
        self.assertLess(release.index("python3 tools/release_evidence.py verify-bundle"),
                        release.index("- name: Publish GitHub Release"))


if __name__ == "__main__":
    unittest.main()
