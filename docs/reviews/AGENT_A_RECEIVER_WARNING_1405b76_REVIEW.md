# Agent A receiver-warning checkpoint

Status: **AWAITING CHATGPT REVIEW**. UI design direction remains partially approved. No native installation, merge or release approval.

Code/artifact source: `1405b760a52820ad01d715a295bb4883e76c41c0`. Base: `01f016b54dfbb15b7fa055e8e99260e9e649a6d3`. Owned branch: feature/agent-a-receiver-warning. [Draft PR15](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/15), stacked on preserved PR6. This versioned report is committed after the code source; later documentation HEAD does not change this artifact identity.

## Inspect actual code

- [Exact source diff](https://github.com/Helminiak/bookmap-orderflow-exporter/compare/01f016b54dfbb15b7fa055e8e99260e9e649a6d3...1405b760a52820ad01d715a295bb4883e76c41c0)
- [Notice/state/timer](https://github.com/Helminiak/bookmap-orderflow-exporter/blob/1405b760a52820ad01d715a295bb4883e76c41c0/src/main/java/com/limacharlie/orderflow/BridgeOperatorNotice.java)
- [Host integration](https://github.com/Helminiak/bookmap-orderflow-exporter/blob/1405b760a52820ad01d715a295bb4883e76c41c0/src/main/java/com/limacharlie/orderflow/BookmapOrderflowExporter.java)
- [Approximate diagnostic snapshot](https://github.com/Helminiak/bookmap-orderflow-exporter/blob/1405b760a52820ad01d715a295bb4883e76c41c0/src/main/java/com/limacharlie/orderflow/LiveBridge.java)
- [Regression/geometry/hierarchy tests](https://github.com/Helminiak/bookmap-orderflow-exporter/blob/1405b760a52820ad01d715a295bb4883e76c41c0/src/test/java/com/limacharlie/orderflow/BridgeOperatorNoticeTest.java)
- [Native checklist/rollback](../NATIVE_REVIEW_CHECKLIST.md)

## Behavior and tradeoffs

A persistent nonmodal notice appears above all three existing tabs, with the review banner. WAITING warns from the first displayed snapshot; CONNECTED separates observed HELLO from ACK-in-memory. DISCONNECTED means same-process reconnect within finite retained continuity, not process restart. INVALID remains irreversible for this session, reports unconfirmed range and explains archive/offline recovery versus fresh session. The safe archive-only choice is an explicit settings action/new session, never automatic.

Only a coalescing1s Swing timer while showing reads volatile/atomic diagnostics; unchanged notices do not rewrite text. No market callback, schema, default, queue or valid protocol behavior changes. Snapshots are approximate diagnostics, not integrity certificates.80% retention advisory is not a guaranteed warning lead time under accelerated bursts. The panel is not a global alarm when closed; assess whether supported Bookmap notification APIs are necessary before native acceptance. No network/disk/JSON parsing occurs in this notice timer. Existing detailed-status UI has separate pre-existing I/O concerns.

## Executable evidence and artifact identity

JDK17.0.20.1/Gradle8.10; required `gradle --no-daemon clean test jar writeFixtureClasspath smokeTestBundle`:31 Java tests pass. Supported Python3.12.14:15 validator tests pass. Six Java/Python loopback cases passed against prior production source cd8fffa; correction1405b76 only changes recovery text/test readiness. Two1405b76 JAR/ZIP rebuilds are byte-identical with clean source manifest.

JAR SHA256: `2e6c5085983df77807f6ae6567aadd71e4ee134317401b9a8b86cf0282242782`.
Windows ZIP SHA256: `c8ce87453f90636c5ced4cddd78d68d55a512df5de2cb8365286654098c778bb`.

[Source PR CI](https://github.com/Helminiak/bookmap-orderflow-exporter/actions/runs/37835832769) and [source push CI](https://github.com/Helminiak/bookmap-orderflow-exporter/actions/runs/37835824586) were running at packet creation; consult actual final results, not inferred PASS. Prior cd8fffa Ubuntu push CI failed awaiting WELCOME in a pre-existing overflow test.1405b76 observes bounded health readiness and prevents repeated test-port selection, preserving delivery assertions; original timeout cause is unconfirmed.

Local Swing previews at font scales1/1.25/1.5 rendered and inspected. [Enlarged-font synthetic preview](assets/receiver-warning-font150.png) shows the full new notice/network controls. It also reveals pre-existing clipping of an explanatory noninteractive label: open follow-up, not a full UI PASS. These are not actual Windows DPI/native tests. Bookmap remains running and unmodified; safe installation window unavailable. Licensed/raw recordings and credentials are excluded.

Qwen local GPU participation is verified through LM Studio loopback listener, CUDA-worker18,462MiB allocation and95–98% sampled RTX5090 utilization during a36.3s request. Preliminary3,434-token suggestions were inspected; incorrect HELLO assumption rejected. A bounded source review returned no final answer with5,939 observed tokens; no retry or sign-off claim. No model commands executed. See sanitized validation JSON; all-layer offload/exclusive GPU use remain unproved.

## Findings ledger

- Codex preliminary review at cd8fffa: no high/critical; P2 conflicting reconnect/start-receiver instruction ACCEPTED, corrected and negative test added in1405b76.
- Fresh incremental Codex static re-review at1405b76: P2 VERIFIED, no new high/critical. Static only; not native or ChatGPT approval.
- ChatGPT architecture/human-factors findings: PENDING, none received. Do not invent reviewer acceptance.

| Gate | Evidence / status |
|---|---|
| Generic callback/protocol preservation | Diff unchanged; existing tests/loopback pass |
| Notice geometry/state/timer | Deterministic tests + inspected Swing render |
| Native Windows DPI100/125/150 | PENDING safe installation |
| Native missing-receiver warning/closed-panel UX | PENDING |
| REALTIME/full-session/resources | PENDING, synthetic results do not certify |
| Durable receiver/recovery/disk-stall bound | OPEN production blockers |
| Independent ChatGPT steering | AWAITING REVIEW |
| Merge/release/Marketplace | NOT AUTHORIZED |

## Questions and next work

1. Does this operator text clearly separate handshake, RAM ACK, recoverable disconnect and irreversible invalidity?
2. Is panel-local warning sufficient, or should a supported nonmodal global Bookmap notification be required? What behavior is appropriate during fast catch-up?
3. Does the prominent review banner/wrapping introduce unacceptable native space/scroll overhead? Inspect actual Windows before approval.
4. Challenge same-process reconnect instructions, threshold wording and independent archive validity; no durable delivery promise.

Next safe engineering: diagnostic JSON/control-character fault coverage and targeted pre-existing explanatory-label clipping, separately checkpointed. Native review requires owner safe window. Agent B owns private receiver/dispatcher/dashboard8767; A retains dashboard8766 and does not edit B's branches. Refresh GitHub every30 active minutes and at interface/checkpoint boundaries. Persistent monitor heartbeat does not claim ongoing agent development after a session ends.
