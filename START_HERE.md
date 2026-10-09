# START HERE — Bookmap exporter / Agent A

This is the **public, non-sensitive** entry point for the Orderflow Bookmap exporter.

**Assigned role:** Agent A runs in **web/cloud Codex** and owns Java/Bookmap exporter, publisher/transport, Configuration UI, Windows compatibility, source-bound packaging and tests. Confirm assignment from owner. Never infer that a cloud Codex `localhost` endpoint reaches the owner's Ubuntu local Qwen server. Local Qwen is preferred when an **owner-authorized secure route already exists**; do not establish tunnels, public endpoints or credentials without permission.

**Complete four-agent startup policy** is held in the owner's *private* authorized repository at [orderflow-entry-engine / START_HERE.md](https://github.com/Helminiak/orderflow-entry-engine/blob/main/START_HERE.md). Retrieve it when your GitHub identity has private repository access. If not authorized, do not attempt circumvention: use this public entry and current PR evidence, then ask for missing private scope. Do not copy private coordination, prompts, tokens or licensed market data here.

**Morning sequence:** Owner's fresh morning RESUME instruction supersedes the previous EOD STOP **only for non-disruptive previously scoped development**. Read the completed A handoff and `handoff/CURRENT_STATE.md` on the PR19/PR20 development branches; inspect newest remote HEADs, source-versus-documentation SHAs, PR stacking, exact-source ChatGPT review decisions, and associated CI. Pending WELCOME diagnostic and LAN IPv4 reviews are separate gates; do not infer approvals from older source or the staged uninstalled preview. Inventory dirty/unpushed work before any Git operation; no reset/clean/force-push. Reconstruct handoff if missing. Investigate pinned Java HELLO/WELCOME flaky failure without masking timeouts. Complete non-disruptive approved tests before source-bound review requests.

**Gatekeeper:** web ChatGPT GPT-6 or later gives exact-source review decisions using `CHATGPT_REVIEW_V1`. A local model, web Codex, a green CI build or stale approval does **not** approve subsequent commits. No merging, releasing, Bookmap JAR installation/restart, native feed tests, trading, credential or network changes without explicit owner authorization.

**Daily A prompt:**
```text
I am Orderflow Agent A in Web Codex. OWNER MORNING RESUME: I authorize today's non-disruptive development within the existing reviewed scope, not installation, restart, live capture, trading, merge, or release. Read this repository's START_HERE.md and the private canonical START_HERE/policy v1.1 only if authorized. Recover the newest A handoff, worktree status, PR19 and stacked PR20 or successors, source-versus-docs HEADs, CI and web ChatGPT GPT-6+ exact-SHA review gates. Preserve local work and reconcile outstanding review requests before editing. Use owner-approved secure Qwen delegation only if actually deployed and qualified; otherwise continue authorized web Codex. Report MORNING_STATUS, then execute the next permitted Java task and checkpoint it to GitHub.
```

At end of day, commit and verify nonsecret source/doc/test checkpoints and prepare `docs/handoff/AGENT_A_MORNING_RESUME_<date>.md` with SHA and unpushed-file inventory. Do not claim a successful push until remote HEAD verifies it.
