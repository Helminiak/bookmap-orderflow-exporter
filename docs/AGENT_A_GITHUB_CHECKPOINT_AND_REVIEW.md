# Codex Agent A: GitHub Checkpoints, Bookmap Native Review and ChatGPT Steering
**Public-repository engineering protocol — 2026-10-08**

This is proposed process documentation on a separate branch. It does not merge, release, install a JAR or alter the active Bookmap session. It complements README.md, AGENTS.md and HANDOFF_MASTER.md. The private accelerated replay/model research plan and implementation belong only in the PRIVATE orderflow-entry-engine repository and must not be copied here.

## 1. Authority and scope

- Codex A: implement/test the public Bookmap acquisition, journal, bridge, UI, Java JAR and Windows acceptance workflow.
- Codex B: implement/test private Linux receiver, dual dashboards, local Qwen RTX 5090 dispatcher and offline accelerated replay/dataset infrastructure; owner of private work. Do not modify another agent's branch.
- Both Codex A and Codex B may **use** the shared RTX 5090/Qwen service. Codex A's existing working connection is not to be disrupted by B's orchestration changes. Local API inference must be verified; a cloud token does not prove GPU execution.
- ChatGPT is the independent engineering/design/human-factors reviewer and steering advisor. It can read GitHub source/PRs through the connected app **when asked**; do not claim automatic review or approval. The owner retains authorization for restart, installation, merges and release.

## 2. Required GitHub check cadence for Agent A

**At each task start:** fetch/refresh remote refs safely, inspect actual local and remote branch SHA/dirty status, PR #6, relevant issues, CI, latest human/ChatGPT findings, this document and HANDOFF_MASTER.md. Where allowed, read private long-term roadmap and cross-repo interface contract.

**At least every 30 minutes while a Codex task is actually active:** recheck upstream PR/issue/comments/CI and relevant private/public contract state. Compare branch SHA and detect concurrent modifications. This does not wake an inactive cloud task and does not justify invented ACTIVE dashboard status.

**Immediately before any** protocol/event-schema or bridge state-machine change, branch integration, native Bookmap installation, review checkpoint packaging or session handoff: repeat remote SHA/PR/CI check.

**At major checkpoints and session end:** commit to isolated owned branch, push without force, confirm remote exact SHA, update PR and versioned Markdown review index with changed-file links and evidence, and leave a reproducible handoff including next task/blocker. Do not overwrite draft PR #6 or main.

If private GitHub access is unavailable, never invent its content; raise a scoped dependency request and use only publicly allowed interface facts.

## 3. Observed native UX defect and provisional acceptance

Current UI design direction is **PARTIALLY APPROVED** for continued native evaluation, not final UI/transport or commercial release acceptance.

Owner observed v0.5a with Linux bridge enabled but receiver not started: retained queue fills, Live status says waiting for receiver, bridge becomes INVALID without adequate early actionable warning. This must be addressed in the visible GUI, not merely in logs. The finite retention INVALID state is correct fail-closed behavior; warning and recovery usability are deficient.

Required operator states: BRIDGE DISABLED; WAITING FOR RECEIVER; CONNECTED with actual handshaking/ACK evidence; RECOVERABLE DISCONNECTED with contiguous retained window; INVALID with nonrecoverable completeness loss; ERROR/bind/protocol failure.

Proposed warning: nonblocking/persistent early "Linux Bridge — Receiver Unavailable" in Bookmap; show receiver-start instructions and an explicit safe option to disable bridge delivery for a new session. Use one-shot/non-spam notification on state transition. Do not auto-disable or auto-relabel INVALID as HEALTHY; user must consent to any settings-changing action. Do not claim starting the receiver after exhaustion restores lost seq.

Never block Bookmap market callbacks or Swing EDT with networking/UI alerts. The archive can remain independently valid even when optional bridge delivery is INVALID; the two statuses and their evidence must remain distinct. Handshake and ACK are not fsync/durability.

Regression test matrix must include disabled startup, no receiver, brief reconnect, retention pre/exhaustion, HISTORY vs REALTIME disconnect, startup validation, one-shot warning, operator disable, invalid-state persistence, archive independence and GUI latency.

## 4. Native review JAR and safety gate

Finish and package version-identifiable JAR from verified source; record source HEAD and SHA256 JAR hash, reproducible tests, Windows integration and rollback. In Bookmap show persistent "FOR REVIEW — NOT PRODUCTION APPROVED" without obstructing Configuration, Live status, Information and controls. Link or export an operator review/defect report. Use one installed exporter addon only; Bookmap must be closed for JAR replacement. Never interrupt an active trading/Bookmap connection without a separately authorized safe window.

Synthetic JDK17/Gradle/Python test PASS does not establish actual Windows Bookmap DPI (100/125/150), sustained load, recovery, durable bridge or marketplace readiness. Retain these as separate acceptance gates.

## 5. GitHub-native ChatGPT review artifacts

At every major architecture, UI, reliability, packaging or performance checkpoint, commit a versioned Markdown entrypoint on the **exact branch being reviewed**:

  docs/reviews/AGENT_A_<MILESTONE>_<SHORT_SHA>_REVIEW.md

Optionally maintain docs/reviews/REVIEW_INDEX.md as a pointer. Include exact repository/branch/head/base SHA, PR URL, changed file/diff links, current implementation design, UI and failure-mode evidence, CI link with passed/failed/skipped status, sanitized benchmark logs, source/JAR hashes, reproduction steps, open risks, and prioritized questions for ChatGPT.

The owner should be able to request "Review Agent A's latest Bookmap exporter PR and GitHub review index" in ordinary ChatGPT; GitHub is the actual evidence source. ZIP/Markdown downloads are supplementary where GitHub lacks needed artifacts, not a mandatory manual upload every review. No raw licensed capture, proprietary entry-quality logic, private source, tokens or customer data enters this public repository.

ChatGPT's actual review response must be captured as dated findings with code SHA and each item marked NEW/ACCEPTED/VERIFIED/DEFERRED/REJECTED and a technical disposition. ChatGPT provides steering; it does not automatically approve or release.

## 6. Shared GPU and resource/cost rules

Codex A may use its existing **verified** local Qwen endpoint immediately for scoped preliminary reviews and test-case proposals. Agent B supplies a shared job dispatcher and bounds concurrency, initially one active job per 32 GB RTX 5090 until profiling supports more. Record actual model, job attribution, latency, token usage and compute telemetry where available. Never claim local inference if the request was served remotely; cloud localhost is not Ubuntu localhost. Qwen cannot perform independent ChatGPT sign-off or prove code correctness; run deterministic regression checks.

## 7. Current dependencies and review entrypoints

Active exporter review: https://github.com/Helminiak/bookmap-orderflow-exporter/pull/6

Related public issues #7–#12 cover native DPI/load, shutdown, ownership, memory/storage, bridge security, provenance. Maintain separate evidence on resolved vs outstanding items.

Private cross-repository dependencies must be checked by authorized Agent A through Helminiak/orderflow-entry-engine's published review/governance plan and receiver contract, without exposing proprietary content here.

No force-push, hidden acceptance downgrades, unapproved Bookmap restart, automatic merge, publication or execution/broker controls.
