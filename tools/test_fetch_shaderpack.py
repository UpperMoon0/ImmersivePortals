"""Pinned pack download behavior; uses no network."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import fetch_shaderpack


class ShaderpackFetchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = b"pinned shaderpack bytes"
        manifests = self.root / "tools/shaderpacks"
        manifests.mkdir(parents=True)
        (manifests / "real-packs.json").write_text(json.dumps({"makeup": {
            "filename": "pack.zip", "url": "https://cdn.modrinth.com/pinned.zip",
            "sha256": hashlib.sha256(self.payload).hexdigest(), "profile": "no_effects"}}))
        patcher = patch.object(fetch_shaderpack, "ROOT", self.root)
        patcher.start()
        self.addCleanup(patcher.stop)

    def response(self, payload):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = payload
        return response

    def test_download_requires_hash_and_reuses_verified_cache(self):
        with patch.object(fetch_shaderpack.urllib.request, "urlopen", return_value=self.response(self.payload)) as request:
            path, profile = fetch_shaderpack.fetch("makeup", self.root / "downloads")
            self.assertEqual(path.read_bytes(), self.payload)
            self.assertEqual(profile, "no_effects")
            fetch_shaderpack.fetch("makeup", self.root / "downloads")
            request.assert_called_once()

    def test_wrong_hash_is_not_staged(self):
        with patch.object(fetch_shaderpack.urllib.request, "urlopen", return_value=self.response(b"wrong release")):
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                fetch_shaderpack.fetch("makeup", self.root / "downloads")
        self.assertFalse((self.root / "downloads/pack.zip").exists())

    def test_unknown_pack_is_not_a_download_destination(self):
        with self.assertRaisesRegex(ValueError, "unknown pinned"):
            fetch_shaderpack.fetch("arbitrary-url", self.root / "downloads")


if __name__ == "__main__":
    unittest.main()
