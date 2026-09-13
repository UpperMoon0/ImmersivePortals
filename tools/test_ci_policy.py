import unittest
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
