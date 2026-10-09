# Engineering closeout — 2026-10-08

## Scope and audited state

Authorized public-exporter closeout only; no training features, exporter operational changes, defaults, unrelated refactors, releases, merges or history rewrites. Runtime/UI anchor d447a6905deec882f67ab7addc8d65ff0a00fa81 was already committed and synchronized before this closeout. Its localized network-row fix uses measured wrapped FlowLayout height, GridBag equal baseline for other rows and as-needed settings scrolling; Apply/status behavior is preserved. This closeout adds documentation, publication exclusions, a synthetic benchmark and CI coverage for that tool.

Inspected local tracked/untracked/ignored files, source/component hierarchy, settings/Apply/status, queues, writer/compression/shutdown, bridge protocol/retention/health, tests, launchers, build/dependency declarations, docs/handoffs and Git history. Fetched public/private remotes, inspected branches, tags, PRs, issues, releases and historical failed checks. Public feature/linux-live-bridge and private feature/live-bridge-receiver were clean and synchronized at audit. Public main remained validated v0.4 at 2e0c0c79df9801a68ff9c3f3332a2ffff558ee2d; private receiver anchor 701a8e408d223c9eefc15d6ed488771323e810dd. No tags/releases existed. No unpublished meaningful source was found; ignored build/cache output is reproducible. All history/branches preserved. Governance PR #1 left independent.

## Verification actually performed

- Fresh JDK 17/Gradle 8.10 clean test/JAR/classpath/Windows-bundle build: PASS, 13 Java tests. Deprecation warnings remain; no configured SpotBugs/Checkstyle suite exists.
- Python 3.12 public canonical validator unit suite: PASS, 15 tests.
- Six authorized private Java/Python bridge integration tests against the fresh public JAR: PASS, 19.04s. These are generic integration checks, not full recertification of private offline research candidates.
- Fresh synthetic actual-callback journal-only benchmark: PASS, 190,003 CRC/schema/summary-validated records, 60,000 final open orders, zero drops/overflows, 2,783,282 compressed bytes. Process wall 3.001235745s (~63,308 records/s), Java child peak RSS 498,925,568 bytes (~475.8 MiB). Includes startup/shutdown/fixture sleep; not a Bookmap slowdown or queue-retention measurement. See PERFORMANCE and sanitized JSON.
- Previously rendered/inspected Linux Swing subsection previews and current geometry tests at font scales 1/1.25/1.5; no actual Windows Bookmap display-DPI inspection claimed. Source anchor CI 37748294289 passed Windows/Ubuntu.
- Earlier independently inspected supplied physical smoke: 60s/118 samples, 4,656 events published and ACKed, no reported bridge/journal/query losses, stable owner/session. Original SMB file is now unavailable; retained aggregates explicitly carry null source hash and availability limitation. Separate 57,771-event PASS is owner-reported. Exact installed JAR SHA absent from report.
- Python syntax, relative document links, staged whitespace, artifact contents and publication/privacy checks performed before commit. Final current-head remote CI is recorded in PR #6's checkpoint report; this document does not claim the older source CI is the final documentation tip.

Reproduction commands/dependencies are in TESTING_AND_TROUBLESHOOTING and PERFORMANCE. CI now also runs a ten-cycle benchmark smoke on Windows/Ubuntu; this checks tool portability, not throughput equivalence across runners.

## Publication and engineering knowledge preserved

Added all requested architecture, pipeline/schema, bridge/persistence, performance/reliability, history/decisions, research roadmap, vision/boundary, roadmap and risk documents. Rewrote README for current state, added AGENTS and HANDOFF_MASTER, updated CHANGELOG/current-state pointer and marked obsolete UI/status notes as historical. Original history remains in Git and milestone records. Added synthetic benchmark source and sanitized aggregate results; broadened ignored local runtimes/profiles/binary datasets with explicit tiny synthetic fixture exceptions. No licensed raw capture, private feature source/criteria/weights, credentials or local personal paths are published in these additions.

Created substantive issue groups #7–#12 after checking for duplicates. Risk register R-01–R-13 distinguishes corrected/resolved facts from reviewed defects, assumptions and untested behavior. Resolved historical CI failures are documented with their cause/fix anchors, not hidden: old JAR path, expected Windows negative-test exit and connection teardown race. Current source verification is green.

Publication uses grouped benchmark/evidence and engineering-handoff commits, normal push to existing draft PR #6, then verifies remote SHA and current-head Windows/Ubuntu CI. Exact evidence anchor is in HANDOFF_MASTER; the final tip is resolvable via git rev-parse HEAD and the PR checkpoint. A tracked document cannot contain its own future hash. No release readiness is inferred from green CI.

## Incomplete and unavailable

Native Bookmap DPI, sustained heavy-load phase-separated slowdown, exceptional shutdown/storage failure tests, callback threading/coherent snapshots, retained heap/GC saturation, authenticated network protection, cross-process snapshots/resume and repeated-BMF initial-state/provider completeness remain open. Shutdown cleanup is a reviewed defect, not fixed by closeout. Full licensed BMF inventory, one summary's paired archive, exact smoke JAR hash, original smoke file at closeout, full private candidate acceptance and some early quantitative design rationale are unavailable. No fabricated tests/results or durability claims replace them.

Resume by reading AGENTS/HANDOFF_MASTER, fetching safely, inspecting current branch/CI, then taking one scoped risk issue. Top five tasks and milestone acceptance are in ROADMAP. Closeout authorization ends at the synchronized engineering checkpoint.

## Subsequent owner authorization

Nightly checkpoint d9da782b052b67b6d151c1198305b22f4cbf29fc was pushed and Windows/Ubuntu CI passed (37750909614 / 37750902487). The owner then authorized another UI attempt with Live status on a tab because configuration was hidden. This extends the original stop condition only for that UI task. See UI_TAB_FOLLOWUP_2026-10-08.md and the current-state/master pointers; the earlier two-panel descriptions in this record refer to the nightly baseline. No new exporter behavior or private features were authorized/implemented.
