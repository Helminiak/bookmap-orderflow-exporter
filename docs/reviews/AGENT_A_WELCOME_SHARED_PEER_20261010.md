# Agent A shared WELCOME peer investigation — 2026-10-10

## Scope and failure evidence

This bounded increment changes Java tests only. The latest PR #19 review is owner comment [6094711300](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19#issuecomment-6094711300), which approves the documentation checkpoint and directs a test-only shared handshake-stage probe. It identifies two confirmed Ubuntu WELCOME failures:

| CI run | Test | Observed failure |
|---|---|---|
| [38029783011](https://github.com/Helminiak/bookmap-orderflow-exporter/actions/runs/38029783011), source `6f649b7` | `SyntheticBridgePeerTest.concurrentPublisherStartupAndFirstHelloRemainObservable()` | At four publishers, local HELLO send accepted, WELCOME missing at about 2.075s, publisher stayed `WAITING_RECEIVER` / receiver `none`; Windows passed. Original review evidence: [PR comment 6094533727](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19#issuecomment-6094533727). |
| [38031167682](https://github.com/Helminiak/bookmap-orderflow-exporter/actions/runs/38031167682), docs-only source `ae0804b` | `BridgeHealthQueryTest.terminalAckWithinRetentionBudgetCompletesSession()` | One publisher: local HELLO send accepted, WELCOME missing at about 2.003s, monitor events 2 and 1, publisher stayed `WAITING_RECEIVER` / receiver `none`; Windows passed. Exact failure detail is in [PR comment 6094711300](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19#issuecomment-6094711300). |

The different fixtures converge on a missing receiver observation before WELCOME. The failures remain preserved in their original GitHub Actions runs; this test-only work does not alter or replace those records.

## Test-only instrumentation and results

`SyntheticBridgePeer` is now the shared single-attempt peer for `BridgeHealthQueryTest` and `SyntheticBridgePeerTest`. It polls the DEALER and its ZeroMQ monitor together, samples publisher `state` and `receiver` while waiting, labels local connect/send and DEALER reply stages, and retains the two-second WELCOME receive budget. It sends no retry HELLO. The synchronized matrix retains its three rounds at 4/8/16 publishers (84 handshakes maximum). The health test exercises the same helper with one publisher. Existing controls still verify a locally accepted HELLO with no route, an intentionally withheld WELCOME, and a direct test ROUTER exchange that explicitly observes HELLO and emits WELCOME.

| Check | Conditions | Result |
|---|---|---|
| Focused health-query + synchronized startup tests | JDK 17.0.20.1, Gradle 8.10, two bounded CPU-load workers; one health-query publisher plus the 4/8/16, three-round matrix | Gradle exit 0; `BUILD SUCCESSFUL`; no WELCOME failure reproduced |
| First full Java run | Same JDK/Gradle; before correcting diagnostic counter expectations | Exit 1; 60 tests, 2 failed. The existing synthetic-control tests expected the status supplier to be called only on assertion failure, but continuous state sampling called it 401 times in the withheld-reply case and 6 times in the fast-success case. This was an instrumentation expectation failure, not a missing WELCOME. |
| Full Java regression and packaging rerun | Same JDK/Gradle, two bounded CPU-load workers, after updating those expectations | Exit 0; 60 tests, 0 failures/errors/skips; `clean test jar writeFixtureClasspath smokeTestBundle` successful |
| Python regression | Python 3.14.4 | Exit 0; 15 tests passed |

The failed first run's session log was retained locally. No generated logs or machine-specific paths are committed. No artificial network operations were used; the scheduler load was two bounded synthetic CPU workers and ended with the test command.

## What the probe establishes

The DEALER monitor's `CONNECTED` event establishes a transport connection, not application-level HELLO receipt. A `CONNECTED` publisher `receiver` value shows that the worker passed through HELLO processing and reached the state update immediately before its WELCOME send branch. `WAITING_RECEIVER` / `none` means the test did not observe that transition during the receive window. The test peer cannot see the internal ROUTER `recv` or the production WELCOME `send` result without production instrumentation, which remains outside scope. The separate controlled ROUTER test explicitly timestamps its own HELLO receive, WELCOME send, and DEALER receive but cannot prove the production worker has identical behavior under CI scheduling.

The local loaded run did not reproduce either CI failure and does not resolve the common-path defect. The two CI failures across different tests increase confidence that the symptom is not unique to one fixture, but they do not distinguish scheduling, transport routing, or production worker behavior. No production Java source was changed.

## Review gate

Request independent GPT-6 review of this exact test-source diff and evidence. Stop at that review gate. No production edit, merge, deployment, Bookmap install/restart, or live capture is authorized by this checkpoint.
