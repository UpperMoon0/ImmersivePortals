import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify
from standing_balloon_test_support import STANDING_MARKERS, write_standing_balloon_evidence


class StandingBalloonEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        result_dir = patch.object(verify, "RESULT_DIR", self.root)
        result_dir.start()
        self.addCleanup(result_dir.stop)

    def test_runner_delegates_correlated_validation_once_and_propagates_failure(self):
        for cold in (False, True):
            with self.subTest(cold=cold):
                write_standing_balloon_evidence(self.root, cold)
                with patch.object(verify, "validate_standing_balloon_evidence",
                                  wraps=verify.validate_standing_balloon_evidence) as validate:
                    verify.validate_results(0, standing_balloon=True, standing_cold_start=cold)
                    validate.assert_called_once_with(cold)
                with patch.object(verify, "validate_standing_balloon_evidence",
                                  side_effect=RuntimeError("invalid correlated receipt")):
                    with self.assertRaisesRegex(RuntimeError, "invalid correlated receipt"):
                        verify.validate_results(0, standing_balloon=True, standing_cold_start=cold)

    def test_runner_requires_each_marker_and_independent_native_client_samples(self):
        for marker in STANDING_MARKERS:
            with self.subTest(marker=marker):
                write_standing_balloon_evidence(self.root)
                (self.root / (marker + ".txt")).unlink()
                with self.assertRaisesRegex(RuntimeError, "Missing standing balloon evidence"):
                    verify.validate_results(0, standing_balloon=True)
        for case in (0, 1):
            for key, value in (("supported", False), ("samples", 39), ("maximum", 1)):
                with self.subTest(case=case, key=key):
                    write_standing_balloon_evidence(self.root)
                    native = dict(supported=True, samples=40, maximum=.01) | {key: value}
                    (self.root / f"balloon-standing-client-{case}.json").write_text(json.dumps(native))
                    with self.assertRaisesRegex(RuntimeError, "Invalid native standing client samples"):
                        verify.validate_results(0, standing_balloon=True)
            write_standing_balloon_evidence(self.root)
            (self.root / f"balloon-standing-client-{case}.json").unlink()
            with self.assertRaisesRegex(RuntimeError, "Missing native standing client samples"):
                verify.validate_results(0, standing_balloon=True)

    def test_runner_rejects_client_exit_and_failure_markers(self):
        write_standing_balloon_evidence(self.root)
        with self.assertRaisesRegex(RuntimeError, "Standing balloon client failed"):
            verify.validate_results(1, standing_balloon=True)
        for side in ("server", "client"):
            with self.subTest(side=side):
                failure = self.root / f"{side}-fail.txt"
                failure.write_text("flight failed")
                with self.assertRaisesRegex(RuntimeError, "flight failed"):
                    verify.validate_results(0, standing_balloon=True)
                failure.unlink()

    def test_consolidation_keeps_both_original_and_standing_oracles_required(self):
        workflow = (verify.ROOT / ".github/workflows/ci.yml").read_text()
        standing = workflow.split("  standing-balloon:", 1)[1].split("  sable-ship-flight:", 1)[0]
        powered = workflow.split("  sable-ship-flight:", 1)[1].split("  graphical-e2e:", 1)[0]
        self.assertIn("transport: [tcp]", standing)
        self.assertIn("ships-aeronautics --ship-size control --standing-followup", powered)
        self.assertNotIn("continue-on-error", standing + powered)
        write_standing_balloon_evidence(self.root)
        with patch.object(verify, "validate_ship_reports") as original:
            for marker in ("ships-server-pass.txt", "ships-client-pass.txt"):
                with self.assertRaisesRegex(RuntimeError, "preceding powered-suite"):
                    verify.validate_results(0, ships=True, aeronautics=True, standing_balloon=True)
                original.assert_not_called()
                (self.root / marker).write_text("mounted observations passed")
            verify.validate_results(0, ships=True, aeronautics=True, standing_balloon=True)
            original.assert_called_once_with(aeronautics=True, ship_size="all")
