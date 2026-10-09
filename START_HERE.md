# START HERE — Bookmap exporter / Agent A

This is the **public, non-sensitive** entry point for the Orderflow Bookmap exporter.

**Assigned role:** Agent A runs in **web/cloud Codex** and owns Java/Bookmap exporter, publisher/transport, Configuration UI, Windows compatibility, source-bound packaging and tests. Confirm assignment from owner. Never infer that a cloud Codex `localhost` endpoint reaches the owner's Ubuntu local Qwen server. Local Qwen is preferred when an **owner-authorized secure route already exists**; do not establish tunnels, public endpoints or credentials without permission.

**Complete four-agent startup policy** is held in the owner's *private* authorized repository at [orderflow-entry-engine / START_HERE.md](https://github.com/Helminiak/orderflow-entry-engine/blob/main/START_HERE.md). Retrieve it when your GitHub identity has private repository access. If not authorized, do not attempt circumvention: use this public entry and current PR evidence, then ask for missing private scope. Do not copy private coordination, prompts, tokens or licensed market data here.

**Morning sequence:** read current A handoff(s) on development branch, inspect PR19/PR20 (and newer PRs), branch HEAD/stacking, exact-source ChatGPT review comments, and CI. Inventory dirty/unpushed work before any Git operation; no reset/clean/force-push. Reconstruct handoff if missing. Investigate pinned Java HELLO/WELCOME flaky failure without masking timeouts. Complete non-disruptive approved tests before source-bound review requests.

**Gatekeeper:** web ChatGPT GPT-6 or later gives exact-source review decisions using `CHATGPT_REVIEW_V1`. A local model, web Codex, a green CI build or stale approval does **not** approve subsequent commits. No merging, releasing, Bookmap JAR installation/restart, native feed tests, trading, credential or network changes without explicit owner authorization.

**Daily A prompt:**
```text
I am Agent A, web Codex. Read this repository's START_HERE.md, then the private canonical START_HERE and policy only if authorized; read latest A handoff/current PR19/20 or successors and exact-SHA ChatGPT approvals. Preserve any local work, verify remote branches/CI and model route, report MORNING_STATUS, and begin the highest-priority authorized Java exporter task. Use Qwen for bounded assistance only when securely reachable by existing permission; otherwise work through the authorized web Codex environment. Keep owner-only operational gates closed.
```

At end of day, commit and verify nonsecret source/doc/test checkpoints and prepare `docs/handoff/AGENT_A_MORNING_RESUME_<date>.md` with SHA and unpushed-file inventory. Do not claim a successful push until remote HEAD verifies it.
