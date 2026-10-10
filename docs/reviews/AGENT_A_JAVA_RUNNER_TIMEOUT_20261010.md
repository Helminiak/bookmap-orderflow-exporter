# Agent A bounded Java runner timeout review packet

Status: request independent review of the corrected exact source on PR #21. This is a manual test-runner and test/docs change only. It preserves the existing WELCOME investigation and makes no Bookmap, production exporter, CI, or automatic-execution changes.

## Scope and behavior

The existing review of source `499f331e1536788748304d82bf259509657c0e02` requested bounded timeout cleanup, incomplete-cleanup reporting, a corrected review-index link, and supported-toolchain verification. This checkpoint changes the runner to drain output continuously while retaining at most 4 MiB, bound process termination and output-drain waits, and report incomplete cleanup when the direct process or captured output cannot be confirmed closed. It does not claim universal process-tree cleanup: descendants that detach and close inherited stdout cannot be detected by this CLI. The detached-descendant test verifies bounded return and incomplete reporting, then terminates its fixture under an independent outer watchdog.

The fixed command set and Gradle failure status propagation remain intact. A poisoned README fixture verifies that a successful runner call does not execute its contents, invoke a shell/process/Git operation beyond the fixed test command, or modify the fixture repository. The runner itself has no Git staging/push, README interpretation, authorization record, or model-lock implementation.

## Verification

- `gradle --no-daemon clean test jar writeFixtureClasspath smokeTestBundle`, using JDK `17.0.20.1+1` and Gradle `8.10`: exit **0**; Gradle XML reports **60 tests, 0 failures, 0 errors, 0 skipped**; all eight tasks succeeded.
- Actual CLI invocation of `tools/run_java_tests.py` with that JDK/Gradle pair and a 600-second deadline: exit **0**, including preflight and all fixed Gradle tasks.
- `set -o pipefail; python3 -m unittest discover -s tests -v 2>&1 | tee /tmp/agent-a-python-regression-final.log`: exit **0**; **30 tests passed**, **0 failures/errors/skips** (15 runner-contract and 15 validator tests).
- Focused synthetic runner tests: **15 passed** as included in the complete Python suite. Coverage includes direct-child socket release, managed-descendant socket release, detached-descendant inherited-pipe watchdog, bounded output, simulated Windows `taskkill` failure, exact failure status propagation, and poisoned README behavior.
- A disposable Git fixture distinguished a modified tracked file, deleted tracked file, and untracked file using `git status --porcelain=v1 -uall`; all three states were detected as expected.
- A pre-staging audit matched the complete worktree to seven approved paths: six tracked modifications and this one intentional untracked review packet. It found no deletions, symlinks or path escapes. `git diff --check` was clean.

The initial sandbox-only focused run returned **1** with one failure and one error because loopback socket creation was denied (`PermissionError: [Errno 1] Operation not permitted`). The initial sandbox-only Gradle run returned **1** because Gradle could not load `libnative-platform.so`. Both raw statuses were retained; the authorized outside-sandbox reruns passed. The final Python pipeline used `pipefail`, so `tee` could not mask the unittest status.

The environment has Python **3.14.4**, not Python 3.12; no Python 3.12 executable was found. The full suite therefore passed on 3.14.4, and 3.12-specific compatibility remains unverified. This repository's prescribed Python suite is `unittest`; no pytest suite was run. The Windows process termination branch is covered by a simulated partial-`taskkill` failure, not a Windows host run.

## Scope limits and requested decision

Git status/path authorization, expired/corrupt authorization HOLD behavior, and model-lock contention are not implemented by this public CLI and are not connected to it. The test-success result cannot authorize a commit or any changed path. Before staging this checkpoint, the agent separately inspected the complete changed-path list, checked it against the exact task allowlist, and confirmed there were no unexpected untracked files, deletions, symlinks or path escapes; only the approved explicit paths will be staged. These external-dispatcher requirements remain a gate before any automatic execution; no automatic execution is enabled here.

Request: review the pushed source SHA identified in the PR comment for the bounded output/termination implementation, exact exit behavior, adversarial child-process tests, documentation and scope limits. Do not treat this request as approval to merge, deploy, install, activate automation, or change the WELCOME production source.
