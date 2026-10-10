# Recovery draft foundation — REVIEW PENDING CHATGPT

Exact base `8c77a7b60d7122cd536440b24b65dae707bf3ad0`; code source `33fcd9da45df590bc30d7ef01f4e074bfb54ce92`. Assigned branch `agent-a/native-bridge-recovery-20261009`; [draft PR19](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19), stacked on PR15. Later report commits are not new validated runtime/JAR source.

## Review request and exact diff

[Code-only diff](https://github.com/Helminiak/bookmap-orderflow-exporter/compare/8c77a7b60d7122cd536440b24b65dae707bf3ad0...33fcd9da45df590bc30d7ef01f4e074bfb54ce92). Added only `src/main/java/com/limacharlie/orderflow/BridgeRecoveryDraft.java` and `src/test/java/com/limacharlie/orderflow/BridgeRecoveryDraftTest.java`.

Request ChatGPT review of immutable baseline, reversible unsaved selection/pending semantics and whether this dependency-free helper is justified for the next recovery-controls prototype. **No actual ChatGPT review or approval is recorded.** This is a foundation, not the completed issue17 UX. The helper is deliberately not yet referenced by the existing exporter UI and cannot affect an active session.

## Behavior and independent verification

`selectArchiveOnly()` changes only the current boolean choice false; `setBridgeEnabled()` can restore it. `pending()` compares it against immutable initial preference. No save, Apply, reload, Settings, LiveBridge, Swing, threads, file/network access or runtime-validity API exists. No callback, protocol, journal, control/default or INVALID behavior changed. EDT confinement is a caller contract, not enforced synchronization. Initial preference means saved/UI baseline, not proof of active transport state.

Local Qwen implement route produced the two files in a separate public worktree at the exact base; no cloud model implementation or other agent worktree edits. Response completed with2030 input/2847 output tokens (1355 reasoning tokens). A missing final outer JSON brace was repaired before decoding; the two allowlisted source strings were otherwise unchanged. Codex reviewed every source/test line, applied the existing Java formatter, then ran tests with real tools. Qwen did not run the tests. Two pre-request launcher mistakes (prompt formatting and invalid router task name) failed before inference; no code was accepted from them. An early focused-test invocation before decoded files existed failed “no matching tests”; corrected invocation passed, not a suppressed failure.

Seven new behavior tests: enabled/disabled baselines, pending archive-only choice with immutable baseline, cancellation/reversal, idempotence, reverse transition from initial disabled, and independent draft isolation. These verify draft semantics; they do not prove UI input, manual Apply, settings persistence or native reachability. Those require the later integrated controls tests.

## Commands and results

- Separate-worker worktree JDK17/Gradle8.10 `test --tests com.limacharlie.orderflow.BridgeRecoveryDraftTest`: exit0,7 tests passed.
- Exact-source `clean test jar writeFixtureClasspath smokeTestBundle`: exit0,43 Java tests passed, zero failures/errors/skips.
- Python3.12 `-m unittest discover -s tests -v`: exit0,15 tests passed.
- Staged diff/whitespace and public-data review passed; no raw captures, credentials/private files or local absolute paths published.
- Uninstalled local JAR SHA256 `b464bedab70c84cfdec9a94b24c86773dbc71761c35806cdd22f4bbb5e3b9871`; manifestsource33fcd9d and clean-tree flag verified. All25 tested main class files match packaged entries. This artifact was retained privately, never copied into the installed addon/Windows Downloads, and is not a release.
- [Exact-source Windows/Ubuntu PR CI37883079352](https://github.com/Helminiak/bookmap-orderflow-exporter/actions/runs/37883079352): PASS on both Windows and Ubuntu (completed; no rerun). This is an observed success, not a closure of the known intermittent WELCOME issue.

## Risks, limitations and rollback

No user-visible recovery control is implemented yet. Native Bookmap recovery, actual100/125/150%DPI and under-load reachability remain PENDING. An ancestor-scroll-aware action strip/keyboard prototype and explicit manual-Apply/settings tests are the next low-risk development milestone; putting a button in BorderLayout NORTH alone is not sufficient to guarantee it stays visible when an outer viewport scrolls.

Existing post-readiness terminal WELCOME CI failure remains OPEN, independently of Windows access. A clean local pass does not fix that intermittent transport/fixture issue. No transport changes were made here. Issue16 local IPv4/adapter selection/manual-migration remains a separate proposal. Shared local model job reported by AgentB was not interrupted; no further inference job launched after that notification.

Rollback of this unreferenced helper is removal/revert of these two additions on the assigned branch; no installed artifact, persisted settings or active session recovery required. All owner-only gates remain: no Windows JAR replacement/install, Bookmap/exporter restart, feed/capture/trading/security changes, merge/release or deployment without specific authorization.

## Next safe action

ChatGPT review this exact-source packet; record its actual response before claiming approval. While review is pending, develop isolated synthetic recovery-control/manual-Apply tests and the bounded UI prototype, keeping production transport changes out. Do not ask the owner for routine engineering GO; escalate only owner-reserved actions under [review policy](AGENT_A_CHATGPT_REVIEW_POLICY.md).
