"""Real temporary Git repositories exercise the runner's progress and interruption handling.

No product runtime or feature QA is started by these tooling tests.
"""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import signal
import sys
import time
import tempfile
import unittest
from unittest.mock import patch

import yaml

from sdlc_lib import model, runner


class RunnerProgressTest(unittest.TestCase):
    feature_dir = "features/F07-example"
    stage_dir = feature_dir + "/stages/S01-example"

    def git(self, *args):
        result = subprocess.run(["git", *args], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def write(self, name, value):
        path = Path(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-m", "test: fixture source")
        return self.git("rev-parse", "HEAD")

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)
        self.git("init", "-b", "feature")
        self.git("config", "user.email", "qa@example.invalid")
        self.git("config", "user.name", "Tooling QA")
        self.write(".gitignore", "qa/reports/\n.local/\n__pycache__/\n")
        self.write("qa/checks/F07/pass.sh", "#!/bin/bash\nexit 0\n")
        self.write("docs/brd/requirements.yaml", "requirements:\n- id: REQ-01\n  text: Works\n")
        stage = {"id": "F07-S01", "sequence": 1, "status": "complete", "qa_status": "not-run",
                 "verification": {"commands": ["true"], "automation": [
                     {"id": "works", "tool": "script", "file": "qa/checks/F07/pass.sh"}]}}
        feature = {"id": "F07", "status": "in-qa", "qa_status": "not-run",
                   "verification": {"automation": [{"id": "journey", "tool": "script",
                                                    "file": "qa/checks/F07/pass.sh"}]}}
        self.write(self.stage_dir + "/stage.yaml", yaml.safe_dump(stage))
        self.write(self.feature_dir + "/feature.yaml", yaml.safe_dump(feature))
        self.commit()
        self.output = io.StringIO()
        self.quiet = contextlib.redirect_stdout(self.output)
        self.quiet.__enter__()

    def tearDown(self):
        self.quiet.__exit__(None, None, None)
        os.chdir(self.previous)
        self.tmp.cleanup()

    def test_heartbeat_incremental_counts_and_unique_report_ids(self):
        self.write("qa/checks/F07/pass.sh", "#!/bin/bash\necho secret-child-output\nsleep 0.12\nexit 0\n")
        self.commit()
        entries = runner.select(model.load(), feature="F07")
        snapshots = []
        real_write = runner._write_summary
        def capture(report, summary):
            real_write(report, summary)
            snapshots.append(json.loads((Path(report) / "summary.json").read_text()))
        with patch.object(runner, "_write_summary", side_effect=capture):
            code, report = runner.run(entries, "F07", heartbeat_seconds=0.03)
        self.assertEqual(code, 0)
        self.assertTrue(any(s["current"]["index"] == 1 and s["completed"] == 0 for s in snapshots if s["current"]))
        self.assertTrue(any(s["completed"] == 1 for s in snapshots))
        self.assertGreater(len(snapshots), 5)
        spawned = [s for s in snapshots if (s.get("current") or {}).get("child_pid")]
        self.assertTrue(spawned)
        self.assertEqual(spawned[0]["runner"]["pid"], os.getpid())
        self.assertTrue(spawned[0]["runner"]["process_start"])
        self.assertEqual(spawned[0]["current"]["child_pgid"], spawned[0]["current"]["child_pid"])
        self.assertTrue(spawned[0]["current"]["log"].endswith(".log"))
        self.assertEqual(snapshots[-1]["passed"], 2)
        self.assertIn("PROGRESS F07 1/2 F07-S01 works completed=0 pass=0 fail=0 elapsed=", self.output.getvalue())
        self.assertNotIn("secret-child-output", self.output.getvalue())
        self.assertNotEqual(runner.run(entries, "F07")[1], report)


    def test_interruption_leaves_atomic_nonpassing_progress(self):
        entries = runner.select(model.load(), feature="F07")
        original_popen = subprocess.Popen
        def interrupt_child(argv, *args, **kwargs):
            if argv[0] == "bash":
                raise KeyboardInterrupt
            return original_popen(argv, *args, **kwargs)
        with patch.object(runner.subprocess, "Popen", side_effect=interrupt_child):
            with self.assertRaises(KeyboardInterrupt):
                runner.run(entries, "F07")
        summary_path = next(Path("qa/reports").glob("*/summary.json"))
        data = json.loads(summary_path.read_text())
        self.assertEqual(data["state"], "interrupted")
        self.assertEqual(data["completed"], 0)
        self.assertFalse(Path(str(summary_path) + ".tmp").exists())


    def test_empty_selection_and_dry_run_create_no_evidence(self):
        self.assertEqual(runner.run([], "F07"), (2, None))
        self.assertEqual(runner.run(runner.select(model.load(), feature="F07"), "F07", dry_run=True), (0, None))
        self.assertFalse(Path("qa/reports").exists())


    @unittest.skipUnless(os.name == "posix", "POSIX child process groups")
    def test_interruption_kills_term_ignoring_child_and_grandchild_with_bounded_wait(self):
        child_code = ("import os,signal,time; from pathlib import Path; "
                      "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                      "Path('.local/grandchild.pid').write_text(str(os.getpid())); time.sleep(30)")
        parent_code = ("import os,signal,subprocess,sys,time\nfrom pathlib import Path\n"
                       "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                       "Path('.local/parent.pid').write_text(str(os.getpid()))\n"
                       f"subprocess.Popen([sys.executable, '-c', {child_code!r}])\n"
                       "time.sleep(30)\n")
        self.write(".local/ignore-term.py", parent_code)
        self.write("qa/checks/F07/long.sh", f"{sys.executable} .local/ignore-term.py\n")
        entry = {"scope": "F07", "id": "long", "tool": "script", "file": "qa/checks/F07/long.sh"}
        original_popen = subprocess.Popen
        def interrupted_child(argv, *args, **kwargs):
            child = original_popen(argv, *args, **kwargs)
            if argv[0] == "bash":
                original_wait = child.wait
                first = True
                def wait(timeout=None):
                    nonlocal first
                    if first:
                        first = False
                        deadline = time.monotonic() + 2
                        while not Path(".local/grandchild.pid").exists():
                            if time.monotonic() > deadline:
                                raise AssertionError("synthetic descendant did not start")
                            time.sleep(0.01)
                        raise KeyboardInterrupt
                    return original_wait(timeout=timeout)
                child.wait = wait
            return child
        started = time.monotonic()
        try:
            with patch.object(runner.subprocess, "Popen", side_effect=interrupted_child):
                with self.assertRaises(KeyboardInterrupt):
                    runner.run([entry], "F07")
            self.assertLess(time.monotonic() - started, 6)
            summary_path = next(Path("qa/reports").glob("*/summary.json"))
            summary = json.loads(summary_path.read_text())
            self.assertEqual(summary["state"], "interrupted")
            self.assertTrue(summary["cancellation"]["kill_sent"])
            self.assertTrue(summary["cancellation"]["reaped"])
            self.assertEqual(summary["current"]["child_pgid"], summary["current"]["child_pid"])
            for name in ("parent", "grandchild"):
                pid = int(Path(f".local/{name}.pid").read_text())
                found = subprocess.run(["ps", "-p", str(pid), "-o", "stat="], capture_output=True, text=True)
                self.assertTrue(not found.stdout.strip() or found.stdout.strip().startswith("Z"),
                                f"synthetic {name} process remained active")
        finally:
            # Clean only PIDs created and recorded by this synthetic fixture if a regression fails.
            for name in ("parent", "grandchild"):
                path = Path(f".local/{name}.pid")
                if path.exists():
                    try:
                        os.kill(int(path.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass


    @unittest.skipUnless(os.name == "posix", "POSIX TERM signal and child groups")
    def test_real_sigterm_cancels_only_the_spawned_group_and_records_interruption(self):
        self.write("qa/checks/F07/term.sh", "sleep 30 &\nwait\n")
        entry = {"scope": "F07", "id": "term", "tool": "script", "file": "qa/checks/F07/term.sh"}
        launch = f"from sdlc_lib import runner; runner.run([{entry!r}], 'F07', heartbeat_seconds=0.1)"
        env = {**os.environ, "PYTHONPATH": str(Path(runner.__file__).resolve().parent.parent)}
        process = subprocess.Popen([sys.executable, "-c", launch], env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        summary_path = None
        pgid = None
        try:
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                paths = list(Path("qa/reports").glob("*/summary.json"))
                if paths:
                    summary_path = paths[0]
                    data = json.loads(summary_path.read_text())
                    pgid = (data.get("current") or {}).get("child_pgid")
                    if pgid:
                        break
                time.sleep(0.01)
            self.assertIsNotNone(pgid, "synthetic runner never persisted its child identity")
            self.assertEqual(data["runner"]["pid"], process.pid)
            started = time.monotonic()
            process.send_signal(signal.SIGTERM)
            self.assertNotEqual(process.wait(timeout=6), 0)
            self.assertLess(time.monotonic() - started, 6)
            data = json.loads(summary_path.read_text())
            self.assertEqual(data["state"], "interrupted")
            self.assertEqual(data["interrupted_signal"], signal.SIGTERM)
            self.assertTrue(data["cancellation"]["reaped"])
            found = subprocess.run(["ps", "-axo", "pgid=,stat="], capture_output=True, text=True)
            active = [line for line in found.stdout.splitlines() if line.split() and
                      line.split()[0] == str(pgid) and not line.split()[1].startswith("Z")]
            self.assertEqual(active, [], "synthetic descendant group remained active")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=3)
            if pgid is not None:
                try:
                    os.killpg(pgid, signal.SIGKILL)
                except ProcessLookupError:
                    pass


    def test_term_handler_is_restored_after_normal_run(self):
        previous = signal.getsignal(signal.SIGTERM)
        runner.run(runner.select(model.load(), feature="F07"), "F07")
        self.assertEqual(signal.getsignal(signal.SIGTERM), previous)



if __name__ == "__main__":
    unittest.main()
