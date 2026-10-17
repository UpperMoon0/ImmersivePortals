import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import verify

def readiness(dimension, keys=("0,0",)):
    return dict(dimension=dimension,world_chunks={k:True for k in keys},plot_chunks={"32,32":True},world_ready=True,plot_ready=True)

def add_correlated_evidence(root, receipts, cold=False):
    for case,row in enumerate(receipts):
        remote=16384+case*8192+8
        keys=[f"{x},{z}" for x in range((remote-12)>>4,((remote+12)>>4)+1)
              for z in range((remote-12)>>4,((remote+12)>>4)+1)] if cold else ["0,0"]
        body=f"00000000-0000-4000-8000-{case+1:012d}"
        nonce=f"00000000-0000-4000-8000-{case+20:012d}"
        row.update(case=case,body=body,player="00000000-0000-4000-8000-000000000099",
                   blocks=484,arrival_y=-40,observed_y=80,source_body_absent=True,destination_body_present=True,
                   source_readiness=readiness("minecraft:the_nether",keys),destination_readiness=readiness("minecraft:overworld",keys),
                   cold_start=cold,cold_nonce=nonce if cold else "")
        client=dict(case=case,body=body,handoff=f"00000000-0000-4000-8000-{case+10:012d}",
                    handoff_completed=True,failed_handoffs=0,source_body_absent=True,destination_body_present=True,
                    supported=True,samples=40,maximum_error=.01,source_readiness=row["source_readiness"],
                    destination_readiness=row["destination_readiness"],cold_start=cold,cold_nonce=nonce if cold else "")
        (root/f"balloon-client-case-{case}.json").write_text(json.dumps(client))
        if cold:
            for side in ("server","client"):
                (root/f"balloon-cold-{side}-{case}.json").write_text(json.dumps(dict(
                    case=case,nonce=nonce,dimension="minecraft:overworld",remote_x=remote,remote_z=remote,
                    samples=20,world_chunks={k:False for k in keys},outside_loaders=True,prepared_before_absence=True)))
    return receipts

class StandingBalloonEvidenceTest(unittest.TestCase):
    def test_missing_case_force_loading_writes_and_detachment_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for name in ("server-pass", "client-pass", "balloon-client-ready-0", "balloon-client-ready-1",
                         "balloon-client-arrived-0", "balloon-client-arrived-1"):
                (root/(name+".txt")).write_text("observed")
            for case in (0,1):
                (root/f"balloon-standing-client-{case}.json").write_text(json.dumps(dict(supported=True,samples=40,maximum=.01)))
            receipts=[dict(standing=standing,support=True,force_loaded=False,native_flight_body_writes=0,standing_error=.01,source_gas=300,arrived_gas=400,server_samples=80)
                      for standing in ("envelope","deck")]
            add_correlated_evidence(root,receipts)
            def validate(data):
                (root/"balloon-server.json").write_text(json.dumps(data))
                verify.validate_results(0,standing_balloon=True)
            with patch.object(verify,"RESULT_DIR",root):
                validate(receipts)
                with self.assertRaises(RuntimeError): validate(receipts[:1])
                for key,value in (("support",False),("force_loaded",True),("native_flight_body_writes",1),("standing_error",1),("source_gas",0),("arrived_gas",0),("server_samples",79)):
                    broken=[dict(row) for row in receipts];broken[0][key]=value
                    with self.assertRaises(RuntimeError):validate(broken)
                for key,value in (("supported",False),("samples",39),("maximum",1)):
                    (root/"balloon-standing-client-1.json").write_text(json.dumps(dict(supported=True,samples=40,maximum=.01)|{key:value}))
                    with self.assertRaises(RuntimeError): validate(receipts)
                (root/"balloon-standing-client-1.json").unlink()
                with self.assertRaises(RuntimeError): validate(receipts)
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
            for case in (0,1):
                (root/f"balloon-standing-client-{case}.json").write_text(json.dumps(dict(supported=True,samples=40,maximum=.01)))
            (root/"balloon-server.json").write_text(json.dumps(add_correlated_evidence(root,[
                dict(standing=kind,support=True,force_loaded=False,native_flight_body_writes=0,standing_error=.01,source_gas=300,arrived_gas=400,server_samples=80)
                for kind in ("envelope","deck")])))
            with patch.object(verify,"RESULT_DIR",root),patch.object(verify,"validate_ship_reports") as original:
                with self.assertRaisesRegex(RuntimeError,"preceding powered-suite"):
                    verify.validate_results(0,ships=True,aeronautics=True,standing_balloon=True)
                original.assert_not_called()
                for name in ("ships-server-pass.txt","ships-client-pass.txt"):
                    (root/name).write_text("mounted observations passed")
                verify.validate_results(0,ships=True,aeronautics=True,standing_balloon=True)
                original.assert_called_once_with(aeronautics=True,ship_size="all")
