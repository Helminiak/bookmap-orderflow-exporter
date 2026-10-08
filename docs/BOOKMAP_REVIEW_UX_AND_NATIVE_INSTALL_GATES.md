# Agent A — Bookmap Human Factors, Accessible Bridge Warning and Native Review Installation
Owner-observed correction request, 2026-10-08. **Implementation and tests not implied by this document.** Current implementation: draft PR #15 stacked on draft PR #6. Keep implementation branches isolated and check exact latest PR head.

## Observed defect: invalid bridge warning cannot be read easily
The native v0.5a transport warning reports:
> Linux Bridge INVALID — transport completeness is no longer proven; outbound buffer overflow; unconfirmed sequence range. Starting a receiver now cannot restore lost events.

The operator finds the dark orange/red foreground on a dark gray Bookmap background difficult to read. Current source \`BridgeOperatorNotice.java\` hardcodes \`new Color(150, 55, 0)\`. **Correct visual contrast, content hierarchy and clarity, not the fail-closed INVALID data-integrity behavior.**

Acceptance:
- Theme-aware error foreground, for dark background a **light red/pink** such as \`#FFB4B4\` as a tested starting point, with WCAG-style normal-text contrast ≥4.5:1 against the *actual rendered* panel background. For light theme choose an appropriately dark red; no hardcoded dark color on dark background. Verify Bookmap look-and-feel runtime theme, not merely a synthetic fixed screenshot. Support accessible alternative text/icon; do not use red alone.
- Title \`LINUX BRIDGE — INVALID\`, one-sentence meaning, explicit cause/seq if known, **three clearly separated actions**: verify local archive for offline recovery, disable optional bridge+Apply for **fresh safe session**, or start Linux receiver **before** a new bridge-enabled session. Avoid long repeated wall-of-text notices; separate details from summary.
- WAITING warning before overflow; recoverable disconnect (same running receiver only) distinct from invalid, and a one-shot nonspam visible alert with panel closed if Bookmap SDK/lifecycle safely supports it.
- Preserve independently measured archive validity; never silently make bridge healthy or imply ACK means durable persistence. Never block Bookmap event callback or EDT.
- Real Bookmap Windows DPI 100/125/150, dark/light if supported, readable/scrollable warnings, accessible text, no clipping, manual failure injection. Preserve source/JAR SHA evidence and tests.

## Native installation: prove which gate is actually blocked

User indicates Agent A may have full Windows-machine control. **Investigate and report actual evidence.** This is NOT an instruction to restart live Bookmap just because machine access is available.

Distinguish:
1. Windows filesystem/GUI/program execution actually available to this task?
2. Bookmap running? Is broker/market-data/trading session active? Are open orders or other disqualifying conditions known?
3. Owner-authorized maintenance/replay window established? Prior scoped approval applies only to a safe installation with Bookmap closed; lack of a safe window still blocks a disruptive restart.
4. Exact candidate source + JAR SHA256, previous known-good JAR backup and rollback instructions, only-one-addon registration, test env readiness verified?

Report exact state: \`STAGED—NO HOST ACCESS\`; \`AWAITING BOOKMAP SAFE CLOSE\`; \`AWAITING SPECIFIC OWNER AUTHORIZATION\`; \`INSTALLED FOR REVIEW + VERIFIED HASH\`; \`NATIVE TESTED + ACTUAL ENV EVIDENCE\`; \`RELEASE BLOCKED\`. Do not generically say “native blocked” without the cause.

If Bookmap is **already closed**, Windows execution access and safe scoped authorization are actually verified, install staged review candidate with rollback and then perform permitted safe/native smoke steps. If Bookmap is open or safety cannot be proved, stage artifacts and request maintenance window; never close/restart, replace loaded JAR, interrupt data/broker/trading, or fabricate native test.

## Recovery before Codex quota exhaustion

Read \`ops/agents/TOKEN_EXHAUSTION_RECOVERY.md\`. User's 5-hour allowance was exhausted at ~1% **before the user-sent checkpoint prompt could execute**. This is real proof that prompts sent at limit are unreliable. Work saved only in local memory or unpushed files is at risk.

No documented reliable machine-readable Codex Desktop five-hour quota percentage is established for this cloud agent. Codex CLI \`/status\` may show usage **in a CLI session**; ChatGPT/Codex Settings → Usage is authoritative user display. Do not use an undocumented session endpoint/scraped auth or invent a quota. Status must show measured source or \`UNKNOWN — periodic checkpoint policy active\`.

Without a verified quota feed, commit/push meaningful scoped changes on owned feature branch at each completed work item, approx every 10–15 minutes of changed source, and before starting lengthy work. Keep quick checkpoint minimal and verify remote SHA. If user-provided/authorized quota is actually observable, checkpoint early by <=35% (plan), <=25% (commit/push), <=15% (checkpoint-only); waiting until 1% is too late.

A private local Git backup/checkpoint process may be implemented only with strict secret/large licensed-data exclusion, explicit owner approval for unattended operation and actual host testing. Do not auto-commit raw untracked secrets or force-push. New session resumes from real branch PR/SHA + local dirty files.

## Required evidence and control

Add failure regression tests, screenshots/contrast sample and actual Bookmap conditions to draft PR #15 and SHA-bound GitHub review index. Publish priority and blockers accurately to :8766 dashboard status; do not spoof active cloud work by refreshing a JSON timestamp. Private monitoring implementation belongs to Agent B; coordinate schema/UX labels through approved contract. No merge/release authorization is granted.
