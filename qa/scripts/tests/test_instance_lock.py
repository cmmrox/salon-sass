"""A QA run and infra/scripts/qa-redeploy.sh exclude each other through one lock on the QA instance.

The holders here are processes this test starts (`sleep`) and stops by their own ids."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from sdlc_lib import runner

REPOSITORY = Path(__file__).resolve().parents[3]
COMMON = REPOSITORY / "infra/scripts/lib/common.sh"


def common_sh(script):
    """Runs bash with infra/scripts/lib/common.sh sourced; returns its output."""
    found = subprocess.run(["bash", "-c", f'. "{COMMON}"; {script}'], capture_output=True, text=True,
                           env={**os.environ, "SALON_LOCAL_INSTANCE": "qa"})
    return found.stdout.strip()


class InstanceLockTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)
        self.lock = Path(runner.INSTANCE_LOCK)
        self.sleepers = []
        self.environ = patch.dict(os.environ, {})
        self.environ.start()
        os.environ.pop("QA_PRODUCT_ROOT", None)
        Path("check.sh").write_text('readlink .local/run/qa-instance.lock > held-during-run.txt\n')
        self.entry = [{"scope": "F07", "id": "check", "tool": "script", "file": "check.sh"}]

    def tearDown(self):
        for sleeper in self.sleepers:
            if sleeper.poll() is None:
                sleeper.kill()
                sleeper.wait()
        self.environ.stop()
        os.chdir(self.previous)
        self.tmp.cleanup()

    def sleeper(self):
        process = subprocess.Popen(["sleep", "60"])
        self.sleepers.append(process)
        return process

    def held_by(self, holder):
        self.lock.parent.mkdir(parents=True, exist_ok=True)
        os.symlink(holder, self.lock)

    def run_once(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code, report = runner.run(self.entry, "F07")
        return code, report, out.getvalue()

    def test_the_run_holds_the_lock_from_start_to_end_and_releases_it(self):
        code, report, _ = self.run_once()
        self.assertEqual(code, 0)
        self.assertTrue(Path("held-during-run.txt").read_text().startswith(f"{os.getpid()} "))
        self.assertFalse(os.path.lexists(self.lock))
        self.assertTrue(json.loads((Path(report) / "summary.json").read_text())["state"] == "complete")

    def test_a_live_holder_refuses_the_run_before_its_build_check(self):
        sleeper = self.sleeper()
        self.held_by(f"{sleeper.pid} {runner._started_utc(sleeper.pid)}")
        with patch.object(runner, "build_check", side_effect=AssertionError("checked the build")):
            code, report, out = self.run_once()
        self.assertEqual((code, report), (2, None))
        self.assertIn(f"the QA instance is in use by process {sleeper.pid}", out)
        self.assertIn("qa-redeploy.sh", out)
        self.assertFalse(Path("qa/reports").exists())
        self.assertEqual(os.readlink(self.lock), f"{sleeper.pid} {runner._started_utc(sleeper.pid)}")

    def test_a_stale_lock_is_recognised_and_replaced(self):
        gone = subprocess.Popen(["true"])
        gone.wait()
        for holder in (f"{gone.pid} Mon Jan  1 00:00:00 2024", "999999 Mon Jan  1 00:00:00 2024", "garbage"):
            if os.path.lexists(self.lock):
                os.unlink(self.lock)
            self.held_by(holder)
            code, _, _ = self.run_once()
            self.assertEqual(code, 0, holder)
            self.assertFalse(os.path.lexists(self.lock), holder)
        # A live process whose id matches but whose start time does not is a reused id: stale.
        sleeper = self.sleeper()
        os.unlink(self.lock) if os.path.lexists(self.lock) else None
        self.held_by(f"{sleeper.pid} Mon Jan  1 00:00:00 2024")
        self.assertEqual(self.run_once()[0], 0)

    def test_an_interrupted_run_releases_the_lock(self):
        original = subprocess.Popen
        seen = []
        def interrupt_the_entry(argv, *args, **kwargs):
            if argv[0] == "bash":  # the entry, not the runner's own ps and git calls
                seen.append(os.readlink(self.lock))
                raise KeyboardInterrupt
            return original(argv, *args, **kwargs)
        with patch.object(runner.subprocess, "Popen", side_effect=interrupt_the_entry):
            with self.assertRaises(KeyboardInterrupt), contextlib.redirect_stdout(io.StringIO()):
                runner.run(self.entry, "F07")
        self.assertTrue(seen and seen[0].startswith(f"{os.getpid()} "))
        self.assertFalse(os.path.lexists(self.lock))

    def test_the_lock_is_in_the_product_checkout(self):
        product = Path("product")
        product.mkdir()
        os.environ["QA_PRODUCT_ROOT"] = str(product)
        self.assertEqual(runner.instance_lock_path(os.environ), str(product / runner.INSTANCE_LOCK))

    def test_the_holder_is_written_and_read_as_common_sh_does(self):
        sleeper = self.sleeper()
        written_by_shell = common_sh(f'printf "%s %s" {sleeper.pid} "$(process_started {sleeper.pid})"')
        self.assertEqual(written_by_shell, f"{sleeper.pid} {runner._started_utc(sleeper.pid)}")
        self.assertTrue(runner._holder_alive(written_by_shell))
        held = runner.take_instance_lock(str(self.lock))
        self.assertEqual(common_sh(f'is_same_process "{held}" && echo alive'), "alive")
        runner.release_instance_lock(str(self.lock), held)
        self.assertFalse(os.path.lexists(self.lock))


if __name__ == "__main__":
    unittest.main()
