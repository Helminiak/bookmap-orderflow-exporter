# Agent A checkpoint — owner-resumed WELCOME investigation

The owner explicitly resumed one bounded, manually supervised task after the previous STOP. The current checkpoint is test-only source `617ed5a0507ab7769efd00ab85c3d1c7758c63f2` on `agent-a/native-bridge-recovery-20261009` / PR19. Independent source review is pending; stop at that gate. PR20 remains the separate stacked LAN task and is unchanged.

The added synthetic tests distinguish locally queued HELLO with no route from a ROUTER that received HELLO but withheld WELCOME. A 16-handshake concurrent startup probe passed; the intermittent CI failure was not reproduced. The known null-WELCOME failure stage is confirmed; its trigger is unresolved and no production fix is claimed. Full local checks: 60 Java tests plus package/bundle smoke tasks and 15 Python tests passed. See [source-bound packet](../docs/reviews/AGENT_A_WELCOME_DELIVERY_STAGE_617ed5a_REVIEW.md).

All prior owner-only restrictions remain: no Bookmap install/restart/activation/Apply, live capture, market operations, deployment, merge, release, credentials or security changes. No unattended execution.

## Read first

1. [Complete morning handoff](../docs/handoff/AGENT_A_MORNING_RESUME_20261009.md): inventory, exact source/branch anchors, CI, local-only recovery paths, literal first commands and next five tasks.
2. Private `Helminiak/orderflow-entry-engine`, branch `ops/abc-three-agent-coordination-20261009`, `coordination/agents/a.json`; latest agent review policy and private request protocol. Source orders may change; do not infer RESUME from a review approval alone.
3. Existing independent review comments on [PR19](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19) then [PR20](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/20).

## Branches / current verified engineering

PR19: `agent-a/native-bridge-recovery-20261009`, existing tree `$HOME/Documents/bookmap-orderflow-exporter-agent-a`. Test-only diagnostics source59ca1e8b4968b092fb35dcfdcc2ea16fb51ce98f; evidence HEAD before shutdownff33cd0b3b2d451404c52406e3742cc2bbecc0cf. Actual recovery controls already integrated in production, explicit Apply preserved. New tests58 Java/15 Python and Windows/Ubuntu CI pass.200 baseline+200 instrumented runs did not reproduce intermittent WELCOME failure; do not claim fixed.

PR20: `agent-a/lan-ipv4-selection-20261009`, existing tree `$HOME/Documents/bookmap-orderflow-exporter-agent-a-lan-ipv4`, stacked on PR19 branch. Exact source5c0e37f2c52c87efd3fad3a80bd53b12d91c8067, evidence checkpointdc48306553a0d36a00f05dd484d633f1f6621a7a. Real Configuration Auto/manual local LAN detection/selector and before-mutation Apply validation; metadata off EDT.70 Java/15 Python and Windows/Ubuntu CI pass. Conservative Auto is explicit opt-in for new/legacy wildcard; decision pending review. Metadata filtering is heuristic; native serializer/DPI/dark theme acceptance pending.

Final shutdown documentation SHAs are in STOPPED_CHECKPOINT PR comments and remote refs, separate from tested/source-bound artifact SHAs. All appropriate scratch prototypes have separate archival branch checkpoints; not accepted release code. No dirty source is silently discarded. Full inventory in handoff.

## Approvals and pending queue

Approved exact recovery source83af7d48d6c53f4c0a50f7eb440e13653878e7e0: development and UNINSTALLED Windows Downloads staging only. File `bookmap-orderflow-exporter-BRIDGE-RECOVERY-PREVIEW-83af7d4.jar`, SHA25656d20468607e02811ca0f9278890793e2a2a78b19939d4f0e852808403a7d0cc,624885bytes. No installed/native acceptance claim. Earlier33fcd9d/303b6ca approvals are narrow historical foundations. Do not request unchanged approval again.

Pending, in order: PR19 diagnostics59ca1e8 [request](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19#issuecomment-6075327853); PR20 LAN5c0e37f [request](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/20#issuecomment-6075758032). No matching actual independent decision observed at shutdown. Do not create duplicate requests or infer from Qwen/older approval. New substantive changes require fresh exact-SHA review.

## Next / blocked

After owner RESUME, validate latest scope/reviews, correct findings on appropriate branches, and preserve stacked relationship. Root-cause WELCOME and legacy overflow remain OPEN. Native Bookmap/physical DPI/owner Apply are blocked on explicit owner test authorization. Do not install new LAN artifact: local candidate only. Existing narrow directory/checkbox layout limitation documented; no whole-panel acceptance claim.

Local evidence/scripts/logs/JARs/configuration/credentials/licensed raw captures are **NOT BACKED UP TO GITHUB/OFF-DEVICE**. Safe source/review summaries/screenshots are pushed. Private manifest and local duplicate archive are under `$HOME/Documents/orderflow-local-validation/agent-a-shutdown-20261009/`; original assets retained. Local duplicate is not disk-loss protection. Retained earlier LAN stash is redundant and exact-hash recoverable, never blindly pop shared stash.

Dashboard8766 and LM Studio1234 are preserved. Existing RTX5090 Qwen qualification is historical verified evidence, no new inference at shutdown. Expert prompt router/single-Qwen lock paths and credential-safe recovery details in complete handoff. A supervisor STOPPED keeps scope-only checks every600s, disables review polling and never wakes development. Do not start duplicate watcher/services. Explicit RESUME is required before changing A state back to WORKING/WAITING_FOR_CHATGPT.
