# Bounded Java test-runner contract

`tools/run_java_tests.py` is a one-shot command interface for a future restricted job runner. It builds and tests this public repository only. It does not configure CI, install a background service, launch Bookmap, touch a JAR installation, or deploy anything.

## Invocation

Supply explicit JDK and Gradle executable paths so the runner cannot silently select a different toolchain:

```sh
python3 tools/run_java_tests.py \
  --java-home "$JAVA_HOME" \
  --gradle "$GRADLE_BIN" \
  --timeout-seconds 600
```

The runner requires both `java` and `javac` with major version 17, and Gradle version 8.10. Each tool-version preflight has a 15-second process-group deadline. It sets `JAVA_HOME` and prepends that JDK's `bin` directory to `PATH`. The only build command is the fixed sequence `--no-daemon clean test jar writeFixtureClasspath smokeTestBundle`, run from the repository root. The default build deadline is 600 seconds; the accepted range is 1–1800 seconds.

## Exit and failure behavior

| Exit | Meaning |
|---:|---|
| 0 | All fixed Java regression, packaging and bundle smoke tasks passed. |
| 2 | Toolchain preflight failed: missing/non-runnable JDK or Gradle, wrong Java major, wrong Gradle version, or a version probe failure. Gradle is not launched. |
| 124 | The build deadline expired. The runner requests termination of the managed process group (POSIX TERM then KILL; Windows bounded `taskkill /T /F` with process kill fallback), emits captured output, and does not retry. It reports whether the direct process stopped and the output pipe reached EOF. |
| 125 | Gradle could not be started after preflight. |
| 126 | Gradle reported success, but the runner could not confirm the direct process/output cleanup. |
| Other nonzero | Gradle's positive exit status is returned unchanged. A test failure, test-fixture bind/startup error, or Gradle build failure remains a Gradle failure with its original combined output. Signal termination is represented as the conventional shell status `128 + signal`. |

The runner does not parse test names to downgrade or reinterpret failures. In particular, a loopback port-bind failure remains a failing Gradle test and keeps its original exit status and diagnostic text. A background reader continuously drains combined output while retaining at most 4 MiB; excess bytes are discarded and a truncation marker is appended. Execution, termination and output-drain waits are bounded, so a descendant that deliberately detaches while retaining stdout cannot make the caller wait indefinitely. Such a case is reported as incomplete cleanup. The runner can verify the direct process, managed process-group termination request and output EOF, but cannot prove cleanup of descendants that detach and close their inherited output handle. Callers should retain stdout and stderr as the job log.

## Fixture lifecycle and negative cases

Java tests that start sockets or `LiveBridge` instances must close them with try-with-resources or `finally`, including startup-failure paths. The runner creates no extra socket fixtures or temporary project files. The contract tests verify direct-child timeout cleanup and managed descendant cleanup by rebinding synthetic loopback sockets. An adversarial detached descendant test verifies bounded return and incomplete-cleanup reporting while an outer watchdog enforces a test deadline; the test then terminates the detached process. Process-group termination is not a universal proof that every possible descendant has exited.

`tests/test_java_test_runner.py` covers missing JDK and Gradle, wrong JDK and Gradle versions, exact nonzero propagation from a failed Gradle test, preservation of a synthetic `Address already in use` fixture error, Gradle process startup failure, bounded output capture, direct and descendant timeout cleanup, detached-descendant incomplete reporting, simulated Windows `taskkill` failure, and a poisoned README that cannot trigger shell/process execution, Git push, or fixture-repository modification through this CLI. These cases use synthetic processes/results and do not contain Bookmap data or production code.

This contract is not approval for unattended scheduling or a privileged runner. Any future job service, credentials, network policy, workflow integration, or execution outside a manually supervised checkout requires separate review and authorization.

The CLI does not read README content, inspect or modify Git state, stage files, push commits, implement authorization records, or manage model locks. A successful test exit is evidence about the fixed test command only; it cannot authorize changed paths or Git operations. Any external dispatcher must validate authorization before task execution, use trusted/allowlisted tool paths, detect untracked/modified/deleted files, and independently enforce the approved path scope before staging. Those controls are outside this public one-shot runner and remain a separate review requirement before automatic execution.

Gradle resolves dependencies according to the repository's existing build configuration. This CLI does not add network permissions or configure credentials; a restricted future host must provide its approved dependency cache or separately authorize the required dependency access.
