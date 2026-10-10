# Agent A WELCOME matched comparison — 2026-10-10

## Scope and authorization

This is a bounded, test-only comparison under the current PR #19 authorization and review gate. It makes no production-source changes and performs no Bookmap, live-capture, deployment, or merge operation. The latest `CHATGPT_REVIEW_V1` is PR #19 comment [6094636736](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19#issuecomment-6094636736): it approves the exact diagnostic source `cdfe9817b5a3cfba8ed8a563966ba32bf36852ff` for development and calls for a bounded comparison against baseline `6f649b7c0ce8490957fbee6f66d796293e85c169`. The review records exact-head Windows and Ubuntu CI run [38030721357](https://github.com/Helminiak/bookmap-orderflow-exporter/actions/runs/38030721357) as passing.

## Method and results

Both revisions were checked out as detached worktrees at their exact full commit IDs and tested sequentially on the same Linux host, with Temurin/OpenJDK 17.0.20.1, Gradle 8.10, and 32 reported logical CPUs. The focused test was `SyntheticBridgePeerTest.concurrentPublisherStartupAndFirstHelloRemainObservable`, run with Gradle `--no-daemon test --tests ...`. Each run uses synchronized HELLO starts at 4, 8, and 16 publishers, keeps the existing two-second WELCOME receive budget, and checks the first START event.

| Revision | Runs | Handshakes per run | Total | Gradle exit codes |
|---|---:|---:|---:|---|
| Baseline `6f649b7c0ce8490957fbee6f66d796293e85c169` | 3 | 28 | 84 | 0, 0, 0 |
| Diagnostic `cdfe9817b5a3cfba8ed8a563966ba32bf36852ff` | 1 | 84 | 84 | 0 |

All four runs reported `BUILD SUCCESSFUL`; no assertion failures or test exceptions were observed. The raw Gradle logs were retained in the session's temporary evidence directory. This matched-count, same-host run did not reproduce the intermittent WELCOME timeout. It provides a local comparison, not evidence that the underlying intermittent failure is resolved. The configurations differ in how observations are collected: the diagnostic revision polls the DEALER monitor and samples publisher state, while the baseline test reports its existing failure context. No artificial scheduler load or external port-binding probe was applied in this cycle, so those conditions remain untested.

The original positive failure evidence remains [baseline review comment 6094533727](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19#issuecomment-6094533727) and Ubuntu CI run [38029783011](https://github.com/Helminiak/bookmap-orderflow-exporter/actions/runs/38029783011): at four synchronized publishers, a local HELLO send was accepted, WELCOME timed out at roughly 2.075 seconds, and publisher state remained `WAITING_RECEIVER` with receiver `none`; Windows passed. That failure evidence is independent of the all-pass local comparison here.

## Interpretation and limits

These runs do not establish a production root cause. The diagnostic observations can show that the exporter processed HELLO and advanced publisher state before its WELCOME-send branch, but they do not directly expose the result of the internal ROUTER send. No production instrumentation was authorized or added. The port selection avoids collisions within the test JVM, but does not close the operating-system probe/bind timing window. Further comparison should use controlled scheduler load and port-binding instrumentation if authorized, retaining the current bounded wait and all failure assertions.

## Review gate

Source remains unchanged at `cdfe9817b5a3cfba8ed8a563966ba32bf36852ff`; this checkpoint adds only the comparison report. Request an independent GPT-6 review of this evidence and its interpretation on PR #19, then stop at that review gate. Do not infer approval to modify production behavior, merge, deploy, or broaden the test matrix.
