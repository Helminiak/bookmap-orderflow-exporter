# Agent A GitHub review index

Latest separate workstream: [bounded Java test-runner contract](TEST_RUNNER_CONTRACT.md), branch `agent-a/java-test-runner-pilot-20261010`; review pending exact source SHA. The one-shot runner validates JDK17/Gradle8.10, runs the fixed regression/build task set, propagates nonzero status, and terminates its process group at a hard deadline. Synthetic negative-case tests and actual full-suite execution pass. This branch preserves the separate WELCOME investigation; no CI/unattended runner was enabled.

Latest new source: [shared WELCOME peer investigation](AGENT_A_WELCOME_SHARED_PEER_20261010.md), PR19 race branch; two Ubuntu failures are preserved across the synchronized stress and health-query fixtures. Test-only shared diagnostics and bounded scheduler-load regression pass locally; exact cause remains OPEN pending independent review. Earlier [matched comparison](AGENT_A_WELCOME_MATCHED_COMPARISON_20261010.md), [synchronized WELCOME diagnostics cdfe981](AGENT_A_WELCOME_SYNC_DIAGNOSTICS_CDfe981_REVIEW.md), [delivery-stage source617ed5a](AGENT_A_WELCOME_DELIVERY_STAGE_617ed5a_REVIEW.md), [diagnostic source59ca1e8](AGENT_A_WELCOME_DIAGNOSTICS_59ca1e8_REVIEW.md), and source83af7d48 recovery approval/delivery remain distinct checkpoints.

Latest: [real production recovery integration/source83af7d4](AGENT_A_RECOVERY_INTEGRATION_83af7d4_REVIEW.md), PR19, exact-source APPROVED_FOR_DEVELOPMENT and UNINSTALLED Windows preview staged/hash verified.56 Java/15 Python local pass; exact-source Windows/Ubuntu CI PASS; native acceptance PENDING. Owner-controlled checklist included.

Historical: [synthetic recovery controls/source303b6ca](AGENT_A_RECOVERY_CONTROLS_303b6ca_REVIEW.md), PR19, new source REVIEW PENDING CHATGPT; native integration unfinished. Helper33fcd9d approval is separate and preserved.

Historical: [recovery draft/source33fcd9d](AGENT_A_RECOVERY_DRAFT_33fcd9d_REVIEW.md), draftPR19, REVIEW PENDING CHATGPT. This unreferenced foundation does not fix native recovery reachability; actual installed review JAR remains source5f840f3. Routine development policy is [here](AGENT_A_CHATGPT_REVIEW_POLICY.md).

Historical: [v0.5a notice correction/source5f840f3](AGENT_A_NOTICE_LAYOUT_5f840f3_REVIEW.md), **READY FOR OWNER REVIEW; native PENDING; STOPPED; Agent B paused**. Exact-source Windows/Ubuntu push and PR CI pass. Later report commits are separate from artifact source.

Historical: [receiver warning — exact source1405b76](AGENT_A_RECEIVER_WARNING_1405b76_REVIEW.md), **CHATGPT PRELIMINARY REVIEW RECEIVED at1405b76 — UI PARTIALLY APPROVED, native/release pending**, [draft PR15](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/15). Native/production acceptance remains pending.

Preserved [PR6](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/6) and [governance PR13](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/13) are unmerged. Owner-authorized operating directive is on separate docs/exporter-agent-checkpoints-20261008; this implementation branch does not merge that governance work. Each versioned report binds code and artifact SHA separately from later report commits.

Latest owner gate: [Agent A independent qualification / source6712998](AGENT_A_QUALIFICATION_6712998_REVIEW.md), PASS after one rejected truncated attempt; stop until joint authorization, then GO. Documentation checkpoint is separate from reviewed production/artifact source.
