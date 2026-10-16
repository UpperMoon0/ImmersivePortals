import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import verify

class StandingBalloonEvidenceTest(unittest.TestCase):
    def test_missing_case_force_loading_writes_and_detachment_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for name in ("server-pass", "client-pass", "balloon-client-ready-0", "balloon-client-ready-1",
                         "balloon-client-arrived-0", "balloon-client-arrived-1"):
                (root/(name+".txt")).write_text("observed")
            receipts=[dict(standing=standing,support=True,force_loaded=False,native_flight_body_writes=0,standing_error=.01,source_gas=300,arrived_gas=400)
                      for standing in ("envelope","deck")]
            def validate(data):
                (root/"balloon-server.json").write_text(json.dumps(data))
                verify.validate_results(0,standing_balloon=True)
            with patch.object(verify,"RESULT_DIR",root):
                validate(receipts)
                with self.assertRaises(RuntimeError): validate(receipts[:1])
                for key,value in (("support",False),("force_loaded",True),("native_flight_body_writes",1),("standing_error",1),("source_gas",0),("arrived_gas",0)):
                    broken=[dict(row) for row in receipts];broken[0][key]=value
                    with self.assertRaises(RuntimeError):validate(broken)
                (root/"balloon-client-arrived-1.txt").unlink()
                with self.assertRaises(RuntimeError):validate(receipts)

    def test_consolidation_keeps_both_original_and_standing_oracles_required(self):
        workflow=(verify.ROOT/".github/workflows/ci.yml").read_text()
        standing=workflow.split("  standing-balloon:",1)[1].split("  sable-ship-flight:",1)[0]
        powered=workflow.split("  sable-ship-flight:",1)[1].split("  graphical-e2e:",1)[0]
        self.assertIn("transport: [tcp]",standing)
        self.assertIn("ships-aeronautics --ship-size control --standing-followup",powered)
        self.assertNotIn("continue-on-error",standing+powered)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for name in ("server-pass", "client-pass", "balloon-client-ready-0", "balloon-client-ready-1",
                         "balloon-client-arrived-0", "balloon-client-arrived-1"):
                (root/(name+".txt")).write_text("observed")
            (root/"balloon-server.json").write_text(json.dumps([
                dict(standing=kind,support=True,force_loaded=False,native_flight_body_writes=0,standing_error=.01,source_gas=300,arrived_gas=400)
                for kind in ("envelope","deck")]))
            with patch.object(verify,"RESULT_DIR",root),patch.object(verify,"validate_ship_reports") as original:
                with self.assertRaisesRegex(RuntimeError,"preceding powered-suite"):
                    verify.validate_results(0,ships=True,aeronautics=True,standing_balloon=True)
                original.assert_not_called()
                for name in ("ships-server-pass.txt","ships-client-pass.txt"):
                    (root/name).write_text("mounted observations passed")
                verify.validate_results(0,ships=True,aeronautics=True,standing_balloon=True)
                original.assert_called_once_with(aeronautics=True,ship_size="all")
