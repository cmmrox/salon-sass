"""sdlc run checks, once before its first entry, that the API under test is a current build of HEAD,
and records which build it tested. A fake API on this machine answers /api/v1/meta; no product
runtime is started."""
import contextlib
import http.server
import io
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

from sdlc_lib import runner

REPOSITORY = Path(__file__).resolve().parents[3]


class FakeApi:
    """Answers GET /api/v1/meta with {"commit": self.commit} on a free port of this machine."""

    def __init__(self):
        api = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                body = json.dumps({"service": "salon-api", "commit": api.commit}).encode()
                self.send_response(200 if self.path == "/api/v1/meta" else 404)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_args):
                pass

        self.commit = None
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def closed_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class NeedsProductTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)

    def tearDown(self):
        os.chdir(self.previous)
        self.tmp.cleanup()

    def test_what_each_entry_declares_decides(self):
        Path("up.sh").write_text('. qa/shared/checks.sh\nrequire_product_up\nexpect_equal x 1 1\n')
        Path("reader.sh").write_text('. qa/shared/checks.sh\n# no require_product_up: it reads files\nnode x\n')
        for tool in ("playwright", "k6", "maestro"):
            self.assertTrue(runner.needs_product({"tool": tool, "file": "x"}), tool)
        self.assertTrue(runner.needs_product({"tool": "script", "file": "up.sh"}))
        self.assertFalse(runner.needs_product({"tool": "script", "file": "reader.sh"}))
        self.assertFalse(runner.needs_product({"tool": "script", "file": "missing.sh"}))

    def test_the_repository_checks_are_read_as_intended(self):
        os.chdir(REPOSITORY)
        reader = {"tool": "script", "file": "qa/checks/F01/S01-ci-workflow.sh"}
        backfill = {"tool": "script", "file": "qa/checks/F05/S01-category-backfill.sh"}
        self.assertFalse(runner.needs_product(reader))
        self.assertTrue(runner.needs_product(backfill))


class BuildCheckTest(unittest.TestCase):
    def git(self, *args):
        return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, path, text, message):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(text)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)
        self.git("init", "-q", "-b", "feature")
        self.git("config", "user.email", "qa@example.invalid")
        self.git("config", "user.name", "Tooling QA")
        Path(".gitignore").write_text("qa/reports/\n")
        Path(runner.BUILD_SOURCES_FILE).parent.mkdir(parents=True)
        shutil.copy(REPOSITORY / runner.BUILD_SOURCES_FILE, runner.BUILD_SOURCES_FILE)
        Path("qa/checks").mkdir(parents=True)
        Path("qa/checks/up.sh").write_text("require_product_up() { :; }\nrequire_product_up\nexit 0\n")
        Path("qa/checks/reader.sh").write_text("exit 0\n")
        self.built = self.commit("apps/api/Api.java", "class Api {}\n", "build")
        self.api = FakeApi()
        self.api.commit = self.built
        self.environ = patch.dict(os.environ, {"QA_API_URL": self.api.url})
        self.environ.start()
        for name in ("QA_ALLOW_BUILD_MISMATCH", "SALON_LOCAL_INSTANCE"):
            os.environ.pop(name, None)
        self.product = [{"scope": "F07", "id": "up", "tool": "script", "file": "qa/checks/up.sh"}]
        self.reader = [{"scope": "F01-S01", "id": "ci", "tool": "script", "file": "qa/checks/reader.sh"}]

    def tearDown(self):
        self.environ.stop()
        self.api.close()
        os.chdir(self.previous)
        self.tmp.cleanup()

    def run_entries(self, entries):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code, report = runner.run(entries, "F07")
        summary = json.loads((Path(report) / "summary.json").read_text()) if report else None
        return code, summary, out.getvalue()

    def test_a_current_build_is_recorded(self):
        code, summary, out = self.run_entries(self.product)
        self.assertEqual(code, 0)
        self.assertEqual((summary["api_commit"], summary["api_current"]), (self.built, True))
        self.assertTrue(summary["build"]["needed"])
        self.assertEqual(summary["build"]["api_url"], self.api.url)
        self.assertIn(f"build: the API reports commit {self.built}, current", out)

    def test_a_commit_of_tests_only_leaves_the_build_current(self):
        self.commit("qa/api/F07/x.spec.ts", "test('x', () => {});\n", "test: repair")
        code, summary, _ = self.run_entries(self.product)
        self.assertEqual(code, 0)
        self.assertTrue(summary["api_current"])

    def test_a_stale_build_refuses_the_run_and_leaves_no_evidence(self):
        head = self.commit("apps/api/Api.java", "class Api { int fixed; }\n", "fix: the API")
        code, summary, out = self.run_entries(self.product)
        self.assertEqual((code, summary), (2, None))
        self.assertIn("refusing: ENVIRONMENT: nothing was run", out)
        self.assertIn(f"HEAD {head}", out)
        self.assertIn("infra/scripts/qa-redeploy.sh", out)
        self.assertIn("QA_ALLOW_BUILD_MISMATCH=1", out)
        self.assertFalse(Path("qa/reports").exists())

    def test_an_api_that_does_not_answer_refuses_the_run(self):
        with patch.dict(os.environ, {"QA_API_URL": f"http://127.0.0.1:{closed_port()}"}):
            code, summary, out = self.run_entries(self.product)
        self.assertEqual((code, summary), (2, None))
        self.assertIn("did not answer /api/v1/meta", out)

    def test_the_override_runs_and_records_the_build_as_not_current(self):
        self.commit("apps/api/Api.java", "class Api { int fixed; }\n", "fix: the API")
        with patch.dict(os.environ, {"QA_ALLOW_BUILD_MISMATCH": "1"}):
            code, summary, out = self.run_entries(self.product)
        self.assertEqual(code, 0)
        self.assertEqual((summary["api_commit"], summary["api_current"]), (self.built, False))
        self.assertTrue(summary["build"]["mismatch_allowed"])
        self.assertTrue(summary["build_mismatch_allowed"])
        self.assertIn("NOT current", out)

    def test_a_run_that_needs_no_product_does_not_ask_the_api(self):
        with patch.dict(os.environ, {"QA_API_URL": f"http://127.0.0.1:{closed_port()}"}):
            code, summary, out = self.run_entries(self.reader)
        self.assertEqual(code, 0)
        self.assertEqual(summary["build"], {"needed": False, "api_commit": None, "api_current": None})
        self.assertIsNone(summary["api_commit"])
        self.assertIn("the API was not asked", out)

    def test_the_check_runs_once_for_the_whole_run(self):
        calls = []
        real = runner._api_commit
        def counted(url, timeout=10):
            calls.append(url)
            return real(url, timeout)
        with patch.object(runner, "_api_commit", side_effect=counted):
            code, _, _ = self.run_entries(self.product * 1 + [{**self.product[0], "id": "up2"}] + self.reader)
        self.assertEqual(code, 0)
        self.assertEqual(calls, [self.api.url])

    def test_the_dry_run_asks_nothing(self):
        with patch.object(runner, "_api_commit", side_effect=AssertionError("asked")), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(runner.run(self.product, "F07", dry_run=True), (0, None))


if __name__ == "__main__":
    unittest.main()
