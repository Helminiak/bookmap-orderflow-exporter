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
| 124 | The build deadline expired. The runner terminates the Gradle process group (POSIX TERM then KILL; Windows `taskkill /T /F` with process kill fallback), emits captured output, and does not retry. |
| 125 | Gradle could not be started after preflight. |
| Other nonzero | Gradle's positive exit status is returned unchanged. A test failure, test-fixture bind/startup error, or Gradle build failure remains a Gradle failure with its original combined output. Signal termination is represented as the conventional shell status `128 + signal`. |

The runner does not parse test names to downgrade or reinterpret failures. In particular, a loopback port-bind failure remains a failing Gradle test and keeps its original exit status and diagnostic text. Gradle output is captured and written to stdout after the process finishes or is terminated; callers should retain stdout and stderr as the job log.

## Fixture lifecycle and negative cases

Java tests that start sockets or `LiveBridge` instances must close them with try-with-resources or `finally`, including startup-failure paths. The runner creates no extra socket fixtures or temporary project files. At deadline, terminating the isolated process group causes the operating system to release any remaining child-owned sockets. The contract tests verify that a synthetic child holding a loopback port is terminated and the port can be bound again.

`tests/test_java_test_runner.py` covers missing JDK and Gradle, wrong JDK and Gradle versions, exact nonzero propagation from a failed Gradle test, preservation of a synthetic `Address already in use` fixture error, Gradle process startup failure, hard timeout handling, and socket release after timeout. These cases use synthetic processes/results and do not contain Bookmap data or production code.

This contract is not approval for unattended scheduling or a privileged runner. Any future job service, credentials, network policy, workflow integration, or execution outside a manually supervised checkout requires separate review and authorization.

Gradle resolves dependencies according to the repository's existing build configuration. This CLI does not add network permissions or configure credentials; a restricted future host must provide its approved dependency cache or separately authorize the required dependency access.
