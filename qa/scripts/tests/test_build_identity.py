"""The build-identity check Playwright's global setup applies (qa/shared/build-identity.mjs).

Its own tests are node:test; this runs them, so the tooling's selftest covers them too."""
import os
import shutil
import subprocess
import unittest

TESTS = "qa/shared/build-identity.test.mjs"


class BuildIdentityTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("node"), "Node.js is not on PATH")
    def test_the_build_identity_rules(self):
        self.assertTrue(os.path.isfile(TESTS), f"{TESTS} is missing")
        found = subprocess.run(["node", "--test", TESTS], capture_output=True, text=True)
        self.assertEqual(found.returncode, 0, found.stdout + found.stderr)


if __name__ == "__main__":
    unittest.main()
