# Agent A — Current Steering Orders
**Control class:** Advisory technical steering within existing owner-authorized scope. **Owner-approved standing objectives remain in** `ops/agents/CODEX_A_OPERATING_DIRECTIVE.md` and `docs/AGENT_A_GITHUB_CHECKPOINT_AND_REVIEW.md`. This file must not confer new credentials or deployment authority.

## Work ownership and source of truth

- Assigned role: public Bookmap exporter/JAR, operator UI and native testing, reliability and performance.
- Current relevant development: [draft PR #15](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/15), stacked on [draft PR #6](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/6). **Fetch current exact heads at every check; do not trust these as immutable SHAs.**
- Private architecture companion (authorized private access only): `Helminiak/orderflow-entry-engine` governance branch `docs/accelerated-replay-governance-20261008`, original Market Snapshot Plugin branch reconciliation and accelerated-replay roadmap.
- Canonical acquisition is Bookmap BMF HISTORY plus provider REALTIME through **one** Java exporter; R|Trader direct-ingestion exploration was superseded. Do not create a duplicate decoder or entry-scoring module in the public repository.

## Current prioritized steering (2026-10-08, update when evidence changes)

1. **Review existing work before new work.** PR #15 already describes an in-Bookmap nonmodal missing-Linux-receiver warning and `FOR REVIEW` banner. Evaluate exact current implementation and regression evidence; do not rebuild it from scratch.
2. **UX coverage gap to validate:** a warning in the exporter panel may not alert an operator when the panel is not open. Investigate a safe, transition-triggered, non-spam notification using supported Bookmap/Swing mechanisms, without blocking market callbacks or acquiring new privileges. Report native feasibility honestly and make actionable guidance always available from Live status. Prioritize clear separation of WAITING / RECOVERABLE DISCONNECT / irreversibly INVALID and independent archive correctness.
3. **Deliver real Windows review candidate** with source SHA, JAR SHA256, exact native smoke/visual checks, rollback procedure, safe installation gate; existing running Bookmap must not be interrupted without owner authorization.
4. **Release blockers remain independent:** last-callback/STOP counts, HISTORY→REALTIME, sustained native load, disk-stall bounded shutdown, durable recovery, DPI and callback overhead. A Java/Python CI PASS does not certify native Windows behavior or bridge durability.
5. **GPU and review:** Agent A may keep its verified direct Qwen API access on Ubuntu RTX 5090, but coordinate with B's dispatcher before parallel GPU jobs. All model suggestions are advisory; evidence of local compute and useful final answer must be separate.

## Fast coordination protocol

- Treat this file as a **mutable priority queue/steering memo** and the master directive as stable authority. Pull this memo **at each work-item boundary, before shared contract/architecture changes and upon PR review comment/CI events**. An inexpensive on-Ubuntu observer may poll GitHub metadata every 2–5 minutes with conditional requests/caching; a cloud Codex session may read it whenever active. There is no mandatory 30-minute idle wait; the 30-minute cadence in existing policy is the **maximum gap during an active session**, not the preferred reaction time.
- Write precise task status and SHA-bound review indexes; avoid repeated AI calls on unchanged source. On conflicting new steering, preserve current changes, report exact conflict and perform only safe independent work pending resolution.
- **ChatGPT advisory verdict** is not owner permission to close Bookmap, change brokerage connectivity, merge into main, publish or deploy. Never treat owner silence or elapsed time as approval.

## Latest oversight status

Preliminary direction: **ON TRACK, native review pending**. PR #15 asserts unit tests and local Qwen involvement, but this steering note is not independent verification of a Windows Bookmap installation. Review the actual current PR contents before claiming acceptance. This note can be updated by the hourly ChatGPT oversight task only for source-supported significant changes.


## Token-limit recovery and immediate checkpoint
Low remaining Codex allowance: **before attempting another milestone**, follow [Agent A token-exhaustion recovery SOP](../agents/TOKEN_EXHAUSTION_RECOVERY.md), commit/push current reviewed or explicitly WIP code to Agent A's own implementation branch, update exact-SHA handoff and verify remote. If an active Codex session already has <15% allowance, save first; after reset use the SOP recovery bootstrap.

## 2026-10-08 owner feedback — next priority after quota reset

**Read the new public acceptance document:** [BOOKMAP_REVIEW_UX_AND_NATIVE_INSTALL_GATES.md](../../docs/BOOKMAP_REVIEW_UX_AND_NATIVE_INSTALL_GATES.md). Its requests supersede any earlier ambiguous assumption that the current native warning layout or installation blocker is acceptable.

1. **Recover before coding:** The user's Codex allowance hit 0 before the 1%-remaining checkpoint prompt could run. Inspect local dirty/untracked work and actual latest draft [PR #15](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/15) head, preserve/push relevant WIP safely and update handoff. No user prompt should be needed for future checkpoint cadence: 10–15 minutes of actual changed-code work, each scoped milestone, BEFORE long tasks, with quota UNKNOWN unless an official live source is verifiably exposed.
2. **Correct observed contrast problem:** Current `BridgeOperatorNotice.java` hardcodes warning RGB (150,55,0) on dark Bookmap GUI. Use theme-aware high-contrast light red (starting example #FFB4B4 on dark; actual contrast ≥4.5:1) and dark-red compliant light-mode fallback; separately style WAITING vs irreversibly INVALID; preserve nonblocking EDT/callback behavior. Shorten the long severe-error text into cause, consequence and safe options. Test native Windows fonts/theme/DPI; no claim based only on screenshots.
3. **Explain native install precisely:** User believes Agent A has Windows machine control. Confirm whether the *actual execution environment* can operate that Windows host and whether Bookmap is already closed, safe, and owner-scoped installation authorization is satisfied. Report exact gate (no host access / Bookmap running awaiting safe window / specific owner authorization missing). If *already closed* and all prior conditions genuinely met, proceed with previously authorized safe REVIEW candidate install and record JAR SHA + rollback. Never interrupt a running/active Bookmap session merely because remote desktop control is available. If blocked, stage fully.
4. **Dashboard signal accuracy:** Coordinate with Agent B to show actual GitHub checkpoint, last coding/status event and status-file freshness separately; stale status-file timestamp is not proof cloud Codex stopped. Do not solve by inventing heartbeats. If a task fails, show failed job and exact reason without falsely marking the entire agent failed.
5. Keep native shutdown, HISTORY→REALTIME, archive vs bridge invalidity, and commercial release as separate gates. Review the exact latest PR diff before coding.

Immediately re-read this memo at the next active Codex session. No main merge, trading disruption or Marketplace release is authorized.
