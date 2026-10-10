"""Synthetic contract tests for the bounded Java test runner; no Bookmap runtime."""

from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import os
import socket
import subprocess
import sys
import tempfile
import unittest
from io import StringIO
from unittest.mock import patch


ROOT = Path(__file__).parents[1]
SPEC = spec_from_file_location("java_test_runner", ROOT / "tools/run_java_tests.py")
runner = module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(runner)


class JavaTestRunnerContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.java_home = self.root / "jdk"
        self.java_bin = self.java_home / "bin"
        self.java_bin.mkdir(parents=True)
        self.java = self.java_bin / ("java.exe" if runner.os.name == "nt" else "java")
        self.javac = self.java_bin / ("javac.exe" if runner.os.name == "nt" else "javac")
        self.gradle = self.root / ("gradle.exe" if runner.os.name == "nt" else "gradle")
        for path in (self.java, self.javac, self.gradle):
            path.touch()
            path.chmod(0o755)
        self.output = StringIO()

    def good_probes(self):
        return [
            subprocess.CompletedProcess([], 0, 'openjdk version "17.0.20" 2025-01-01', None),
            subprocess.CompletedProcess([], 0, "javac 17.0.20", None),
            subprocess.CompletedProcess([], 0, "Gradle 8.10\nBuild time: synthetic", None),
        ]

    def test_missing_toolchain_fails_before_gradle(self):
        missing = self.root / "missing-jdk"
        with patch.object(runner, "_capture") as capture:
            status = runner.run_job(missing, self.gradle, 5, self.output)
        self.assertEqual(runner.EXIT_TOOLCHAIN, status)
        self.assertIn("toolchain missing", self.output.getvalue())
        capture.assert_not_called()

    def test_missing_gradle_fails_before_build(self):
        missing = self.root / "missing-gradle"
        with patch.object(runner, "_capture") as capture, patch.object(
            runner, "_run_bounded"
        ) as build:
            status = runner.run_job(self.java_home, missing, 5, self.output)
        self.assertEqual(runner.EXIT_TOOLCHAIN, status)
        self.assertIn("Gradle executable missing", self.output.getvalue())
        capture.assert_not_called()
        build.assert_not_called()

    def test_wrong_jdk_version_fails_preflight(self):
        with patch.object(
            runner,
            "_capture",
            return_value=subprocess.CompletedProcess([], 0, 'openjdk version "21.0.1"', None),
        ) as capture, patch.object(runner, "_run_bounded") as build:
            status = runner.run_job(self.java_home, self.gradle, 5, self.output)
        self.assertEqual(runner.EXIT_TOOLCHAIN, status)
        self.assertIn("requires major version 17", self.output.getvalue())
        capture.assert_called_once()
        build.assert_not_called()

    def test_wrong_gradle_version_fails_preflight(self):
        probes = self.good_probes()
        probes[-1] = subprocess.CompletedProcess([], 0, "Gradle 8.11", None)
        with patch.object(runner, "_capture", side_effect=probes), patch.object(
            runner, "_run_bounded"
        ) as build:
            status = runner.run_job(self.java_home, self.gradle, 5, self.output)
        self.assertEqual(runner.EXIT_TOOLCHAIN, status)
        self.assertIn("requires version 8.10", self.output.getvalue())
        build.assert_not_called()

    def test_gradle_test_failure_exit_code_and_output_are_preserved(self):
        with patch.object(runner, "_capture", side_effect=self.good_probes()), patch.object(
            runner, "_run_bounded", return_value=(37, "FAILED: synthetic Java assertion\n", False)
        ) as build:
            status = runner.run_job(self.java_home, self.gradle, 5, self.output)
        self.assertEqual(37, status)
        self.assertIn("FAILED: synthetic Java assertion", self.output.getvalue())
        self.assertEqual(
            [str(self.gradle.resolve()), *runner.GRADLE_TASKS], build.call_args.args[0]
        )

    def test_port_startup_failure_is_not_retried_or_normalized(self):
        message = "java.net.BindException: Address already in use\n"
        with patch.object(runner, "_capture", side_effect=self.good_probes()), patch.object(
            runner, "_run_bounded", return_value=(1, message, False)
        ) as build:
            status = runner.run_job(self.java_home, self.gradle, 5, self.output)
        self.assertEqual(1, status)
        self.assertIn("Address already in use", self.output.getvalue())
        build.assert_called_once()

    def test_gradle_startup_error_has_distinct_status(self):
        with patch.object(runner, "_capture", side_effect=self.good_probes()), patch.object(
            runner, "_run_bounded", side_effect=OSError("synthetic spawn failure")
        ):
            status = runner.run_job(self.java_home, self.gradle, 5, self.output)
        self.assertEqual(runner.EXIT_STARTUP, status)
        self.assertIn("could not start Gradle", self.output.getvalue())

    def test_job_timeout_uses_reserved_timeout_status(self):
        with patch.object(runner, "_capture", side_effect=self.good_probes()), patch.object(
            runner, "_run_bounded", return_value=(runner.EXIT_TIMEOUT, "partial output", True)
        ):
            status = runner.run_job(self.java_home, self.gradle, 2, self.output)
        self.assertEqual(runner.EXIT_TIMEOUT, status)
        self.assertIn("partial output", self.output.getvalue())
        self.assertIn("timed out after 2 seconds", self.output.getvalue())

    def test_timeout_terminates_process_and_releases_fixture_socket(self):
        child = (
            "import socket,time\n"
            "sock=socket.socket()\n"
            "sock.bind(('127.0.0.1',0))\n"
            "print(sock.getsockname()[1], flush=True)\n"
            "time.sleep(30)\n"
        )
        status, output, timed_out = runner._run_bounded(
            [sys.executable, "-c", child], os.environ.copy(), 1.0, self.root
        )
        self.assertTrue(timed_out)
        self.assertEqual(runner.EXIT_TIMEOUT, status)
        port = int(output.strip().splitlines()[0])
        probe = socket.socket()
        self.addCleanup(probe.close)
        probe.bind(("127.0.0.1", port))

    def test_cli_timeout_is_bounded(self):
        with patch("sys.stderr", StringIO()), self.assertRaises(SystemExit) as raised:
            runner._parse_args(
                ["--java-home", str(self.java_home), "--gradle", str(self.gradle), "--timeout-seconds", "1801"]
            )
        self.assertEqual(2, raised.exception.code)


if __name__ == "__main__":
    unittest.main()
