import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import verify
from test_standing_balloon import add_correlated_evidence

class StandingTransferEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        p=patch.object(verify,"RESULT_DIR",self.root);p.start();self.addCleanup(p.stop)
    def fixture(self,cold=False):
        rows=[dict(standing=s,support=True,force_loaded=False,native_flight_body_writes=0,
                   standing_error=.01,source_gas=300,arrived_gas=400,server_samples=80)
              for s in ("envelope","deck")]
        add_correlated_evidence(self.root,rows,cold)
        (self.root/"balloon-server.json").write_text(json.dumps(rows))
    def mutate(self,path,key,value):
        p=self.root/path;data=json.loads(p.read_text());row=data[0] if isinstance(data,list) else data
        row[key]=value;p.write_text(json.dumps(data))
    def test_prepared_and_cold_positive_controls(self):
        for cold in (False,True):
            self.fixture(cold);verify.validate_standing_balloon_evidence(cold)
    def test_flight_damage_and_nonfinite_values_fail(self):
        for key,value in (("blocks",483),("observed_y",-39.5),("observed_y",float("inf")),
                          ("arrival_y",float("nan")),("source_gas",0),("arrived_gas",284),
                          ("server_samples",79),("support",False),("force_loaded",True),
                          ("native_flight_body_writes",1),("standing_error",1)):
            with self.subTest(key=key,value=value):
                self.fixture();self.mutate("balloon-server.json",key,value)
                with self.assertRaises(RuntimeError):verify.validate_standing_balloon_evidence()
    def test_uncorrelated_failed_or_incomplete_handoff_fails(self):
        for key,value in (("body","wrong"),("handoff",""),("handoff_completed",False),
                          ("failed_handoffs",1),("source_body_absent",False),("destination_body_present",False),
                          ("samples",39),("maximum_error",1)):
            with self.subTest(key=key):
                self.fixture();self.mutate("balloon-client-case-0.json",key,value)
                with self.assertRaises(RuntimeError):verify.validate_standing_balloon_evidence()
    def test_chunk_readiness_cannot_be_missing_or_false(self):
        for file in ("balloon-server.json","balloon-client-case-0.json"):
            for stage in ("source_readiness","destination_readiness"):
                for kind in ("world_chunks","plot_chunks","world_ready","dimension"):
                    self.fixture();p=self.root/file;data=json.loads(p.read_text());r=data[0] if isinstance(data,list) else data
                    r[stage][kind]={} if kind.endswith("chunks") else False;p.write_text(json.dumps(data))
                    with self.assertRaises(RuntimeError):verify.validate_standing_balloon_evidence()
    def test_cold_coverage_nonce_samples_and_separation_are_required(self):
        for side in ("server","client"):
            for key,value in (("nonce","wrong"),("samples",19),("samples",601),("world_chunks",{}),("remote_x",0)):
                self.fixture(True);self.mutate(f"balloon-cold-{side}-0.json",key,value)
                with self.assertRaises(RuntimeError):verify.validate_standing_balloon_evidence(True)
        for key in ("outside_loaders","prepared_before_absence"):
            self.fixture(True);self.mutate("balloon-cold-server-0.json",key,False)
            with self.assertRaises(RuntimeError):verify.validate_standing_balloon_evidence(True)
    def test_cold_receipt_cannot_replace_prepared_baseline(self):
        self.fixture(True)
        with self.assertRaises(RuntimeError):verify.validate_standing_balloon_evidence(False)
        self.fixture(False)
        with self.assertRaises(RuntimeError):verify.validate_standing_balloon_evidence(True)
