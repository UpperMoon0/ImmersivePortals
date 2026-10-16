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
        self.run = dict(id=123, run_attempt=2, run_started_at="2026-10-11T15:41:53Z", status="completed", conclusion="success", event="push",
                        head_branch="main", head_sha=self.commit, path=".github/workflows/ci.yml",
                        repository={"full_name": self.repo}, head_repository={"full_name": self.repo})
        self.jobs = [dict(name=name, started_at="2026-10-11T15:42:00Z", completed_at="2026-10-11T15:45:00Z", status="completed", conclusion="success", run_attempt=2,
                          head_sha=self.commit) for name in evidence.required_jobs()]
        self.artifacts = [dict(id=456, created_at="2026-10-11T15:44:00Z", name=evidence.ARTIFACT, expired=False,
                               workflow_run={"id": 123, "head_sha": self.commit})]

    def validate(self):
        return evidence.validate_run(self.run, self.jobs, self.artifacts, self.repo, self.commit)

    def test_complete_exact_commit_main_ci_is_eligible(self):
        self.assertEqual(self.validate()["id"], 456)

    def test_separate_workflow_policy_keeps_exact_tag_identity_and_rejects_old_coverage(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "tag"
            policy = target / ".verification-policy"
            (target / "tools").mkdir(parents=True)
            (policy / "tools").mkdir(parents=True)
            current_jobs = evidence.required_jobs()
            old_jobs = [name for name in current_jobs if not name.startswith(("Sable ship flight", "Standing balloon stack"))]
            self.assertEqual((len(current_jobs), len(old_jobs)), (48, 41))
            (target / "tools/ci-coverage.json").write_text(json.dumps(old_jobs))
            (policy / "tools/ci-coverage.json").write_text(json.dumps(current_jobs))
            for jobs, expected in ((self.jobs, dict(reuse="true", run_id="123", run_attempt="2", artifact_id="456")),
                                   ([job for job in self.jobs if job["name"] in old_jobs], {"reuse": "false"})):
                with patch.object(evidence, "ROOT", policy), \
                     patch.object(evidence, "git_commit", return_value="b" * 40) as policy_commit, \
                     patch.object(evidence, "api", return_value=self.run), \
                     patch.object(evidence, "pages", side_effect=[jobs, self.artifacts]), \
                     patch.object(evidence, "output") as output, \
                     patch.dict(os.environ, GITHUB_REPOSITORY=self.repo), \
                     patch("sys.argv", ["release_evidence.py", "resolve", "--run-id", "123", "--target-commit", self.commit]):
                    evidence.main()
                    output.assert_called_once_with(expected)
                    policy_commit.assert_not_called()

    def test_resolver_explicit_target_sha_is_full_and_default_remains_checkout_commit(self):
        for target in ("", "main", "a" * 39, "a" * 41, "A" * 40):
            with patch("sys.argv", ["release_evidence.py", "resolve", "--target-commit", target]), \
                 patch.object(evidence, "resolve") as resolve, patch("sys.stderr"):
                with self.assertRaises(SystemExit):
                    evidence.main()
                resolve.assert_not_called()
        with patch("sys.argv", ["release_evidence.py", "resolve"]), \
             patch.object(evidence, "git_commit", return_value=self.commit), \
             patch.object(evidence, "resolve", return_value={"reuse": "false"}) as resolve, \
             patch.object(evidence, "output"), patch.dict(os.environ, GITHUB_REPOSITORY=self.repo):
            evidence.main()
            resolve.assert_called_once_with(self.repo, self.commit, "")

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

    def test_failed_only_retry_relabeling_cannot_reuse_an_earlier_bundle(self):
        # Actual GitHub shape from CI 38149170740: attempt 2 still reports its
        # inherited Core job as run_attempt=2, with attempt-1 timestamps.
        core = next(job for job in self.jobs if job["name"] == "Core verification")
        core.update(started_at="2026-10-11T14:55:48Z", completed_at="2026-10-11T14:58:28Z")
        with self.assertRaisesRegex(ValueError, "inherited"):
            self.validate()
        with patch.object(evidence, "api", return_value=self.run), \
             patch.object(evidence, "pages", side_effect=[self.jobs, self.artifacts]):
            self.assertEqual(evidence.resolve(self.repo, self.commit, "123"), {"reuse": "false"})

    def test_any_inherited_job_or_old_artifact_requires_fresh_verification(self):
        for job in self.jobs:
            with self.subTest(job=job["name"]):
                original = job["started_at"]
                job["started_at"] = "2026-10-11T15:41:52Z"
                with self.assertRaisesRegex(ValueError, "inherited"):
                    self.validate()
                job["started_at"] = original
        self.artifacts[0]["created_at"] = "2026-10-11T14:58:01Z"
        with self.assertRaisesRegex(ValueError, "predates"):
            self.validate()

    def test_missing_malformed_naive_and_reversed_times_reject_reuse(self):
        for record, field in ((self.run, "run_started_at"), (self.jobs[0], "started_at"),
                              (self.jobs[0], "completed_at"), (self.artifacts[0], "created_at")):
            for invalid in (None, 123, "not-a-time", "2026-10-11T15:44:00"):
                with self.subTest(field=field, invalid=invalid):
                    original = record[field]
                    record[field] = invalid
                    with self.assertRaises(ValueError):
                        self.validate()
                    record[field] = original
        self.jobs[0]["completed_at"] = "2026-10-11T15:41:59Z"
        with self.assertRaisesRegex(ValueError, "invalid job execution times"):
            self.validate()

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
        expected = ["Select integration checks", "Core verification", "Required verification"]
        for transport in ("udp", "tcp"):
            for networking in ("bundled", "4.3.0+5c124ecf19", "4.3.1+7e3d421f19"):
                expected.append(f"Sable graphical E2E / {transport} / {networking} / normal")
            expected.append(f"Sable graphical E2E / {transport} / 4.3.1+7e3d421f19 / impaired")
        expected.append("Standing balloon stack / tcp")
        ships = workflow.split("  sable-ship-flight:\n", 1)[1].split("  graphical-e2e:\n", 1)[0]
        for flow in re.findall(r"- \{ ([^}]+) \}", ships):
            row = dict(pair.split(": ", 1) for pair in flow.split(", "))
            expected.append(f"Sable ship flight (draft) / {row['mode']} / {row['transport']} / {row['ship_size']} / bundled")
        for group in ("portal-visual", "real-pack-visual"):
            block = re.split(r"\n  [a-z][\w-]*:\n", workflow.split(f"\n  {group}:\n", 1)[1], maxsplit=1)[0]
            for flow in re.findall(r"- \{ ([^}]+) \}", block):
                values = [pair.split(": ", 1)[1] for pair in flow.split(", ")]
                expected.append(f"{group} ({', '.join(values)}) / visual")
        self.assertEqual(len(expected), 48)
        self.assertCountEqual(evidence.required_jobs(), expected)

    def test_fresh_release_keeps_every_ci_visual_configuration(self):
        def members(text):
            return [tuple(sorted(pair.split(": ", 1) for pair in flow.split(", ")))
                    for flow in re.findall(r"- \{ ([^}]+) \}", text)]
        ci = (evidence.ROOT / ".github/workflows/ci.yml").read_text()
        release = (evidence.ROOT / ".github/workflows/release.yml").read_text()
        ci_visual = ci.split("\n  portal-visual:\n", 1)[1]
        release_visual = release.split("\n  compatibility:\n", 1)[1]
        self.assertEqual(len(members(ci_visual)), 30)
        self.assertCountEqual(members(ci_visual), members(release_visual))


    def test_fresh_release_sable_transport_parity_and_aggregate_gate(self):
        """Both CI transports must run in fallback releases; neither may silently disappear."""
        ci = (evidence.ROOT / ".github/workflows/ci.yml").read_text()
        release = (evidence.ROOT / ".github/workflows/release.yml").read_text()
        ci_job = ci.split("\n  graphical-e2e:\n", 1)[1].split("\n  portal-visual:\n", 1)[0]
        release_job = release.split("\n  graphical-e2e:\n", 1)[1].split("\n  compatibility:\n", 1)[0]

        def transports(job):
            entries = re.findall(r"(?m)^\s*transport:\s*\[([^]]+)\]\s*$", job)
            self.assertEqual(len(entries), 1, "a single explicit transport matrix is required")
            return [value.strip() for value in entries[0].split(",")]

        self.assertEqual(transports(ci_job), ["udp", "tcp"])
        self.assertEqual(transports(release_job), transports(ci_job))
        for key in ("networking", "network"):
            pattern = rf"(?m)^\s*{key}:\s*\[([^]]+)\]\s*$"
            self.assertEqual(re.findall(pattern, ci_job), re.findall(pattern, release_job))
        self.assertCountEqual(re.findall(r"- \{ ([^}]+) \}", ci_job), re.findall(r"- \{ ([^}]+) \}", release_job))
        for job in (ci_job, release_job):
            self.assertIn("IP_VERIFICATION_NETWORKING: ${{ matrix.networking }}", job)
            self.assertIn("IP_NETWORK_STRESS:", job)
        self.assertIn("fail-fast: false", release_job)
        self.assertIn("Sable dedicated graphical E2E / ${{ matrix.transport }}", release_job)

        def config_step(job):
            return job.split("      - name: Configure Sable transport\n", 1)[1].split(
                "      - run: chmod +x ./gradlew\n", 1)[0]

        self.assertEqual(config_step(release_job), config_step(ci_job))
        self.assertIn("disable_udp_pipeline = true", config_step(release_job))
        self.assertIn("attempt_udp_networking = false", config_step(release_job))
        self.assertIn("disable_udp_pipeline = false", config_step(release_job))
        self.assertIn("attempt_udp_networking = true", config_step(release_job))
        self.assertIn("name: release-sable-dimension-stack-e2e-${{ matrix.transport }}", release_job)
        for path in ("run-sable-e2e-server/config/sable-common.toml",
                     "run-sable-e2e-client/config/sable-common.toml",
                     "run-sable-e2e-client/config/sable-client.toml"):
            self.assertIn(path, release_job)

        # A matrix needs-result is successful only when BOTH transport jobs succeed.
        # The existing executable gate regression enumerates skipped/failed/cancelled E2E.
        gate = release.split("      - name: Enforce reused or fresh coverage\n", 1)[1].split(
            "\n  release:\n", 1)[0]
        self.assertIn("graphical-e2e", release.split("\n  verified:\n", 1)[1].split("\n  release:\n", 1)[0])
        self.assertIn("E2E: ${{ needs.graphical-e2e.result }}", gate)
        self.assertIn('"$E2E" == success', gate)

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
            for values in itertools.product(("success", "skipped", "failure", "cancelled"), repeat=5):
                env = dict(os.environ, REUSE=reuse, EVIDENCE="success", **dict(zip(("CORE", "E2E", "SHIPS", "VISUAL", "BALLOON"), values)))
                result = subprocess.run([bash, "-e", "-c", script], env=env, capture_output=True)
                expected = reuse in ("true", "false") and all(v == ("skipped" if reuse == "true" else "success") for v in values)
                self.assertEqual(result.returncode == 0, expected, (reuse, values))
        env = dict(os.environ, EVIDENCE="failure", REUSE="true", CORE="skipped", E2E="skipped", SHIPS="skipped", VISUAL="skipped", BALLOON="skipped")
        self.assertNotEqual(subprocess.run([bash, "-e", "-c", script], env=env).returncode, 0)
        publish = workflow.split("\n  release:\n", 1)[1]
        self.assertIn("!cancelled() && needs.evidence.result == 'success' && needs.verified.result == 'success'", publish)

    def test_fresh_release_requires_all_six_ship_rows_or_complete_reused_evidence(self):
        ci = (evidence.ROOT / ".github/workflows/ci.yml").read_text()
        release = (evidence.ROOT / ".github/workflows/release.yml").read_text()
        def ships(text):
            return text.split("\n  sable-ship-flight:\n", 1)[1].split("\n  graphical-e2e:\n", 1)[0]
        ci_job, release_job = ships(ci), ships(release)
        rows = lambda job: re.findall(r"- \{ ([^}]+) \}", job)
        self.assertEqual(len(rows(release_job)), 6)
        self.assertCountEqual(rows(ci_job), rows(release_job))
        self.assertIn("needs: evidence", release_job)
        self.assertIn("if: needs.evidence.outputs.reuse != 'true'", release_job)
        self.assertIn("ref: ${{ inputs.tag && format('refs/tags/{0}', inputs.tag) || github.ref }}", release_job)
        self.assertIn("fail-fast: false", release_job)
        self.assertNotIn("continue-on-error", release_job)
        self.assertIn("if: always()", release_job)
        self.assertIn('python3 tools/verify.py "${{ matrix.mode }}" --ship-size "${{ matrix.ship_size }}"', release_job)
        self.assertIn("--standing-followup", release_job)
        configure = lambda job: job.split("      - name: Configure Sable transport\n", 1)[1].split(
            "      - run: chmod +x ./gradlew\n", 1)[0]
        self.assertEqual(configure(ci_job), configure(release_job))
        for path in ("build/verification-ships*.json", "build/sable-ship-flight*-e2e/**",
                     "${{ env.SHIP_RUNTIME_PREFIX }}-server/server.properties", "${{ env.SHIP_RUNTIME_PREFIX }}-client/options.txt",
                     "${{ env.SHIP_RUNTIME_PREFIX }}-server/crash-reports/**", "${{ env.SHIP_RUNTIME_PREFIX }}-client/logs/**"):
            self.assertIn(path, release_job)
        gate = release.split("\n  verified:\n", 1)[1].split("\n  release:\n", 1)[0]
        self.assertIn("needs: [evidence, core-verification, graphical-e2e, sable-ship-flight, compatibility, standing-balloon]", gate)
        self.assertIn("SHIPS: ${{ needs.sable-ship-flight.result }}", gate)
        self.assertIn('"$SHIPS" == success', gate)
        self.assertIn('"$SHIPS" == skipped', gate)

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

    def test_release_reuse_policy_is_pinned_to_workflow_not_requested_tag(self):
        release = (evidence.ROOT / ".github/workflows/release.yml").read_text()
        selection = release.split("  evidence:\n", 1)[1].split("\n  core-verification:\n", 1)[0]
        policy = selection.split("      - name: Checkout verification policy from the executing workflow\n", 1)[1].split(
            "      - name: Validate every main CI check and artifact origin\n", 1)[0]
        self.assertIn("ref: ${{ github.workflow_sha }}", policy)
        self.assertIn("path: .verification-policy", policy)
        self.assertIn("tools/release_evidence.py", policy)
        self.assertIn("tools/ci-coverage.json", policy)
        self.assertIn("sparse-checkout-cone-mode: false", policy)
        self.assertIn("ref: ${{ inputs.tag && format('refs/tags/{0}', inputs.tag) || github.ref }}", selection)
        self.assertNotIn("working-directory:", selection)
        self.assertIn('target_commit="$(git rev-parse HEAD)"', selection)
        self.assertIn("python3 .verification-policy/tools/release_evidence.py resolve", selection)
        self.assertIn('--target-commit "$target_commit" --run-id "$SOURCE_CI_RUN_ID"', selection)
        self.assertNotIn("python3 tools/release_evidence.py resolve", selection)


if __name__ == "__main__":
    unittest.main()
