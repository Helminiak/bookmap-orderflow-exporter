#!/usr/bin/env python3
"""Run the repository's fixed Java regression contract once, with a hard timeout."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
from typing import TextIO


EXIT_TOOLCHAIN = 2
EXIT_TIMEOUT = 124
EXIT_STARTUP = 125
MAX_TIMEOUT_SECONDS = 1800
PREFLIGHT_TIMEOUT_SECONDS = 15
GRADLE_TASKS = (
    "--no-daemon",
    "clean",
    "test",
    "jar",
    "writeFixtureClasspath",
    "smokeTestBundle",
)
PROJECT_ROOT = Path(__file__).resolve().parents[1]


def _tool_path(directory: Path, name: str) -> Path:
    if os.name == "nt":
        name += ".exe"
    return directory / name


def _is_runnable_file(path: Path) -> bool:
    if not path.is_file():
        return False
    return os.name == "nt" or os.access(path, os.X_OK)


def _major_version(output: str) -> int | None:
    match = re.search(r"\bversion\s+[\"']?(\d+)|\b(?:java|javac)\s+(\d+)", output, re.IGNORECASE)
    if match is None:
        return None
    return int(match.group(1) or match.group(2))


def _capture(command: list[str], env: dict[str, str]) -> subprocess.CompletedProcess[str]:
    status, output, _ = _run_bounded(command, env, PREFLIGHT_TIMEOUT_SECONDS)
    return subprocess.CompletedProcess(command, status, output, None)


def _preflight(java_home: Path, gradle: Path, env: dict[str, str]) -> str | None:
    java_bin = java_home / "bin"
    java = _tool_path(java_bin, "java")
    javac = _tool_path(java_bin, "javac")
    if not java_home.is_dir() or not _is_runnable_file(java) or not _is_runnable_file(javac):
        return "JDK toolchain missing: expected runnable java and javac under JAVA_HOME/bin"
    if not _is_runnable_file(gradle):
        return "Gradle executable missing or not runnable"

    probes = ((java, "-version", "JDK", 17), (javac, "-version", "JDK", 17), (gradle, "--version", "Gradle", 0))
    for executable, argument, kind, expected in probes:
        try:
            result = _capture([str(executable), argument], env)
        except (OSError, subprocess.TimeoutExpired) as error:
            return f"{kind} preflight could not complete: {error}"
        if result.returncode != 0:
            return f"{kind} preflight exited {result.returncode}: {result.stdout.strip()}"
        output = result.stdout or ""
        if kind == "Gradle":
            version = re.search(r"(?m)^Gradle\s+([0-9]+(?:\.[0-9]+)*)\s*$", output)
            if version is None or version.group(1) != "8.10":
                return "Gradle preflight requires version 8.10"
        elif _major_version(output) != expected:
            return f"{kind} preflight requires major version {expected}: {output.strip()}"
    return None


def _terminate_process_tree(process: subprocess.Popen[str]) -> None:
    if os.name == "nt":
        try:
            result = subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
                check=False,
            )
            if result.returncode != 0 and process.poll() is None:
                process.kill()
        except (OSError, subprocess.TimeoutExpired):
            process.kill()
        return

    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        pass
    # Kill any remaining descendants in the isolated process group as well.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def _run_bounded(
    command: list[str], env: dict[str, str], timeout_seconds: float, cwd: Path = PROJECT_ROOT
) -> tuple[int, str, bool]:
    kwargs: dict[str, object] = {
        "cwd": cwd,
        "env": env,
        "stdout": subprocess.PIPE,
        "stderr": subprocess.STDOUT,
        "text": True,
    }
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    process = subprocess.Popen(command, **kwargs)  # type: ignore[arg-type]
    try:
        output, _ = process.communicate(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        _terminate_process_tree(process)
        output, _ = process.communicate()
        return EXIT_TIMEOUT, output or "", True
    return int(process.returncode), output or "", False


def run_job(
    java_home: Path,
    gradle: Path,
    timeout_seconds: int,
    output_stream: TextIO | None = None,
) -> int:
    output_stream = sys.stdout if output_stream is None else output_stream
    java_home = java_home.expanduser().resolve()
    gradle = gradle.expanduser().resolve()
    java_bin = java_home / "bin"
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_bin) + os.pathsep + env.get("PATH", "")

    problem = _preflight(java_home, gradle, env)
    if problem:
        print(f"Java test runner preflight failed: {problem}", file=output_stream)
        return EXIT_TOOLCHAIN

    command = [str(gradle), *GRADLE_TASKS]
    try:
        status, output, timed_out = _run_bounded(command, env, timeout_seconds)
    except OSError as error:
        print(f"Java test runner could not start Gradle: {error}", file=output_stream)
        return EXIT_STARTUP

    if output:
        output_stream.write(output)
        output_stream.flush()
    if timed_out:
        print(f"Java test runner timed out after {timeout_seconds} seconds; process tree terminated.", file=output_stream)
        return EXIT_TIMEOUT
    # Popen reports signal termination as a negative number; expose the conventional shell code.
    return 128 + abs(status) if status < 0 else status


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", required=True, type=Path, help="JDK 17 home directory")
    parser.add_argument("--gradle", required=True, type=Path, help="Gradle 8.10 executable")
    parser.add_argument(
        "--timeout-seconds",
        type=int,
        default=600,
        help=f"hard build/test deadline, 1..{MAX_TIMEOUT_SECONDS} seconds (default: 600)",
    )
    args = parser.parse_args(argv)
    if not 1 <= args.timeout_seconds <= MAX_TIMEOUT_SECONDS:
        parser.error(f"--timeout-seconds must be between 1 and {MAX_TIMEOUT_SECONDS}")
    return args


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    return run_job(args.java_home, args.gradle, args.timeout_seconds)


if __name__ == "__main__":
    raise SystemExit(main())
