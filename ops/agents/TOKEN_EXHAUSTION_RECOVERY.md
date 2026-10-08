# Agent A — Token-Exhaustion Checkpoint and Zero-Context Recovery

**Purpose:** Recover work safely after Codex Cloud allowance/session/context exhaustion, terminal disconnect, or handoff to a fresh Codex conversation. This is durable standing process guidance on a governance branch, not an implementation change, a merge or owner authorization for release.

**Assigned repository:** Helminiak/bookmap-orderflow-exporter
**Agent priority:** Bookmap exporter review JAR, native Windows warning, callback and continuity reliability
**Governance branch:** docs/exporter-agent-checkpoints-20261008
**Current/relevant PRs:** draft PR #15, stacked on draft PR #6; verify latest
**Operating directive:** `ops/agents/CODEX_A_OPERATING_DIRECTIVE.md`
**Mutable steering:** `ops/steering/AGENT_A_CURRENT.md`

## Before the session exhausts its budget

At each meaningful completed work-item boundary, and **whenever remaining usage is estimated to be below 15%, or before a planned stop**, interrupt new feature work and execute an evidence-preserving checkpoint. A time interval (suggested every 15–20 minutes of meaningful changes) is a fallback; do not push trivial empty commits solely for the clock. GitHub polling is not checkpointing.

1. Inspect actual remote/local branches, staged/unstaged/untracked files, existing worktree ownership and latest GitHub PR comments. Do not trust a dashboard's cached HEAD as a live remote verification.
2. Run scoped tests/lint/format/build that are practical within the remaining budget. Record commands, outcome, elapsed time, failed/skipped tests and missing physical native hardware acceptance; never falsely say all passed.
3. Stage **only reviewed, project-relevant changes**, protecting credentials, logs, raw licensed BMF/NDJSON/Arrow/Parquet, model weights, and private research boundaries. Before using `git add -A`, inspect untracked files; do not lose them. Prefer meaningful complete commits; if incomplete work must be saved, an explicitly marked **WIP / INCOMPLETE — NOT REVIEWED** commit on the agent-owned feature branch is acceptable, *not* on main or a protected/release branch. Never weaken safeguards to force tests green.
4. Commit and push to the **same owned feature branch** without force-push, reset, rebasing another agent's branch or merge. Fetch latest remote and check for unexpected divergence first. Verify via remote SHA/PR update that the pushed commit is actually available; if network/push fails, preserve the local commit and report that GitHub does **not** yet contain it.
5. Write/update the local agent handoff, where the implementation branch supports it: `HANDOFF_MASTER.md` or `docs/reviews/REVIEW_INDEX.md`. Preserve the prior handoff's meaningful facts and append the latest timestamp/identity. Include:
   - Agent/repository/branch/base SHA/last local SHA/confirmed remote SHA/PR links/worktree dirty status.
   - Goal, current task, exact completed and partially completed changes, files touched, decisions and non-goals.
   - Tests passed, failed or skipped with logs/evidence references and CI **actual exact-head** outcome.
   - Runtime environment/tools, current dashboard, Qwen server/model availability **without secrets**, outstanding hardware access steps.
   - ChatGPT review requests and findings **with their exact reviewed SHA**, pending owner gates, open defects and cross-agent dependencies.
   - NEXT ONE SAFE ACTION and following 2–3 prioritized tasks, with explicit acceptance criteria and reproduction steps.
   - Unpushed, untracked, unsaved or environment-only work that **cannot** be reconstructed from GitHub; don't claim 100% recovery when this exists.
6. Keep GitHub docs/reviews/REVIEW_INDEX.md accurate for the **actual implementation branch**, with SHA-bound review links. Post an abbreviated PR checkpoint comment after the push and include the full remote SHA and handoff path. A governance-only file is not evidence the active coding branch is checkpointed.
7. Mark **CHECKPOINTED — READY TO RESUME** only after remote SHA and handoff are confirmed; otherwise **PARTIAL CHECKPOINT — LOCAL RECOVERY REQUIRED**. State if a coding session stopped or model budget is exhausted; a running dashboard does not imply active work.
8. Do not burn the final budget on speculative coding. Do not close/restart live Bookmap, replace an active JAR, trade, disclose credentials, merge or publish.

## Minimal post-reset recovery bootstrap (copy/paste into the existing conversation or a new Codex session)

```text
RESUME AGENT A FROM GITHUB AFTER TOKEN/CONTEXT INTERRUPTION.
Assigned repository: Helminiak/bookmap-orderflow-exporter
Governance branch: docs/exporter-agent-checkpoints-20261008
First read: ops/agents/CODEX_A_OPERATING_DIRECTIVE.md
Then read: ops/steering/AGENT_A_CURRENT.md
Recovery SOP: ops/agents/TOKEN_EXHAUSTION_RECOVERY.md

Do not recreate the project or guess prior progress. Verify available tools, local worktree, remote branch/PR head, the latest handoff, review index, CI/tests and ChatGPT steering. Preserve dirty/unpushed work; do not reset or overwrite it. Compare exact local versus GitHub SHAs and report what cannot be recovered from GitHub.

Continue from the most recent VERIFIED checkpoint on the correct agent-owned branch, not main and not another agent's branch. Use only the necessary source diff and current instructions to avoid token waste. Recheck external dependencies, local Qwen availability and cross-agent contracts. Implement the highest-priority safe unblocked task, run tests, commit/push meaningful progress and update the handoff BEFORE the next session limit. No automatic merge, release or active Bookmap interruption.
```

## Recovery algorithm for an actually fresh/empty session

1. Verify identity `Agent A` and repository; if workspace identity disagrees with this document, **stop and ask for clarification**, do not silently swap worktrees or agent roles.
2. Read the named governance files **at the specified remote governance branch**, without checking out that branch over active source work. Read latest owner/ChatGPT steering PR comments and the exact branch-specific implementation handoff/review index.
3. Find the **most recent verified remote implementation SHA** from GitHub, cross-check current PR head/base and local working tree. A screenshot and an earlier PR-body SHA may be stale. If remote advanced unexpectedly, understand the diff before writing.
4. Read only the relevant changed source/tests and failing logs; don't indiscriminately ingest all historical conversations, full licensed datasets or repository history.
5. If local work exists beyond remote, preserve it and decide whether to finish an unpushed commit, safely push existing commits, or document an irrecoverable environment gap. Never fabricate a completed push.
6. Restore known tool environment and optional local GPU only after observing real access. A cloud `localhost` is not Ubuntu's localhost. Do not consume costly Qwen/cloud tokens on identical source/model/prompt already cached.
7. Implement/test/push the next authorized scoped milestone. If the prior checkpoint awaits ChatGPT architectural review, continue **orthogonal reversible** work but do not self-grant the review or owner approval.
8. Resume recurring checkpoints at work-item boundaries and before model/session exhaustion.

## Why both recovery and steering documents matter

The long-lived operating directive defines **authority**; the mutable steering file defines **current priorities**; the implementation branch and PR define **actual code**; the handoff and exact-SHA review index define **what is verified and what comes next**. No single chat transcript or dashboard status may override contradictory current source/CI evidence. GitHub does not automatically store a model's uncommitted mental context, unpushed files, or unobserved local services.
