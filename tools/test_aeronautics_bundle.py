"""No network needed: pinned downloads and temporary runtime staging fail closed."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import aeronautics_bundle as bundle


class AeronauticsBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.results = self.root / "results"
        self.results.mkdir()
        self.runs = (self.root / "server", self.root / "client")
        self.data = b"controlled official bundle fixture"
        spec = dict(bundle.BUNDLE, size=len(self.data), sha256=hashlib.sha256(self.data).hexdigest())
        patcher = patch.object(bundle, "BUNDLE", spec)
        patcher.start()
        self.addCleanup(patcher.stop)

    def download(self, data=None):
        return patch.object(bundle.urllib.request, "urlopen", return_value=io.BytesIO(self.data if data is None else data))

    def test_official_manifest_is_exactly_pinned(self):
        # Check source literals independently of the tiny byte fixture above.
        source = Path(bundle.__file__).read_text()
        self.assertIn('"size": 33121810', source)
        self.assertIn('"sha256": "b531dbe1081ee354f6e47ba4e0443b9caa648dfb8c82013a591a20836245bd84"', source)
        self.assertEqual(bundle.BUNDLE["url"], "https://cdn.modrinth.com/data/oWaK0Q19/versions/Vzp221Un/create-aeronautics-bundled-1.21.1-1.3.1.jar")
        self.assertEqual(bundle.BUNDLE["mods"], {"aeronautics": "1.3.1", "simulated": "1.3.1", "offroad": "1.3.1"})

    def test_download_and_cache_are_both_pinned(self):
        with self.download() as download:
            path = bundle.fetch(self.root / "cache")
            self.assertEqual(path.read_bytes(), self.data)
            download.assert_called_once_with(bundle.BUNDLE["url"], timeout=120)
        with patch.object(bundle.urllib.request, "urlopen") as download:
            self.assertEqual(bundle.fetch(path.parent), path)
            download.assert_not_called()
        path.write_bytes(b"wrong data")
        with self.download() as download:
            self.assertEqual(bundle.fetch(path.parent).read_bytes(), self.data)
            download.assert_called_once()

    def test_truncated_oversized_and_changed_downloads_never_replace_cached_bytes(self):
        cache = self.root / "cache"
        cache.mkdir()
        destination = cache / bundle.BUNDLE["filename"]
        destination.write_bytes(b"invalid prior cache")
        for data in (self.data[:-1], self.data + b"extra", b"x" * len(self.data)):
            with self.subTest(data=data), self.download(data):
                with self.assertRaisesRegex(RuntimeError, "size mismatch|SHA-256 mismatch"):
                    bundle.fetch(cache)
                self.assertEqual(destination.read_bytes(), b"invalid prior cache")
        self.assertEqual(list(cache.iterdir()), [destination])

    def test_network_failure_is_not_a_baseline_fallback(self):
        with patch.object(bundle.urllib.request, "urlopen", side_effect=OSError("offline")):
            with self.assertRaisesRegex(OSError, "offline"):
                with bundle.stage(self.root, self.results, self.runs):
                    self.fail("unverified bundle was staged")
        self.assertFalse((self.results / "aeronautics-provenance.json").exists())

    def test_stage_records_provenance_and_cleans_both_mods_even_on_failure(self):
        for fail in (False, True):
            with self.subTest(fail=fail), self.download():
                try:
                    with bundle.stage(self.root, self.results, self.runs) as evidence:
                        staged = [self.root / name for name in evidence["staged"]]
                        self.assertEqual(len(staged), 2)
                        self.assertTrue(all(path.read_bytes() == self.data for path in staged))
                        self.assertEqual(evidence["sha256"], hashlib.sha256(self.data).hexdigest())
                        self.assertEqual(json.loads((self.results / "aeronautics-provenance.json").read_text()), evidence)
                        if fail:
                            raise RuntimeError("client failed")
                except RuntimeError as error:
                    self.assertTrue(fail)
                    self.assertEqual(str(error), "client failed")
                self.assertTrue(all(not path.exists() for path in staged))
                self.assertTrue((self.root / "build/verification-downloads" / bundle.BUNDLE["filename"]).is_file())

    def test_existing_user_mod_is_never_overwritten_or_deleted(self):
        existing = self.runs[1] / "mods" / ("__ip_verify__" + bundle.BUNDLE["filename"])
        existing.parent.mkdir(parents=True)
        existing.write_bytes(b"preserve this")
        with self.download() as download:
            with self.assertRaisesRegex(RuntimeError, "overwrite"):
                with bundle.stage(self.root, self.results, self.runs):
                    self.fail("existing mod was overwritten")
            download.assert_not_called()
        self.assertEqual(existing.read_bytes(), b"preserve this")

    def test_partial_copy_failure_removes_only_owned_staged_mods(self):
        unrelated = self.runs[0] / "mods/user-mod.jar"
        unrelated.parent.mkdir(parents=True)
        unrelated.write_bytes(b"keep")
        with self.download(), patch.object(bundle.shutil, "copyfileobj", side_effect=OSError("disk full")):
            with self.assertRaisesRegex(OSError, "disk full"):
                with bundle.stage(self.root, self.results, self.runs):
                    self.fail("partial copy was accepted")
        self.assertEqual(list(unrelated.parent.iterdir()), [unrelated])

    def test_stage_refuses_paths_outside_checkout_and_duplicate_targets(self):
        with self.download() as download:
            for runs in ((self.runs[0], self.root.parent / "elsewhere"), (self.runs[0], self.runs[0])):
                with self.subTest(runs=runs):
                    with self.assertRaisesRegex(RuntimeError, "unsafe|distinct"):
                        with bundle.stage(self.root, self.results, runs):
                            self.fail("unsafe path was accepted")
            download.assert_not_called()

    def test_stale_addon_cannot_contaminate_a_baseline_run(self):
        bundle.require_clean_runs(self.runs)
        stale = self.runs[0] / "mods" / ("__ip_verify__" + bundle.BUNDLE["filename"])
        stale.parent.mkdir(parents=True)
        stale.write_bytes(self.data)
        with self.assertRaisesRegex(RuntimeError, "stale Aeronautics"):
            bundle.require_clean_runs(self.runs)
        self.assertEqual(stale.read_bytes(), self.data)


if __name__ == "__main__":
    unittest.main()
