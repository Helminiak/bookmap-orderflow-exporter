# Contributor and agent instructions

This is PUBLIC `Helminiak/bookmap-orderflow-exporter`: acquisition, canonical normalization, validation and generic transport only. Proprietary features, labels, research thresholds, inference and execution belong in PRIVATE `Helminiak/orderflow-entry-engine`. Never publish raw licensed captures, secrets, private source, local absolute paths or model weights.

Before editing, read README.md, HANDOFF_MASTER.md, docs/ARCHITECTURE.md, docs/ENGINEERING_DECISIONS.md, docs/KNOWN_ISSUES_AND_RISKS.md and the assigned issue/PR. User instructions take precedence over this guide. Inspect/fetch Git safely, identify the actual branch/HEAD, check ignored/untracked files and overlapping PRs, and preserve newer work. No destructive resets, history rewriting or force-pushes. The separate governance PR #1 is not merged; do not silently import or merge it.

Keep changes scoped. Preserve callback order, event timestamps, IDs, anomaly evidence, schema/defaults and the single-plugin architecture. Do not convert trades into MBO mutations. Responsive LIVE callbacks must not wait for optional network/disk delivery. Historical waits are intentional and must be documented separately. Flush is not fsync. Do not hide overload or normalize away integrity failures.

Run `gradle --no-daemon clean test jar writeFixtureClasspath smokeTestBundle` (JDK 17, Gradle 8.10) and `python -m unittest discover -s tests -v` (Python 3.12). Use Windows/Ubuntu CI. GUI font-scale geometry is not actual Bookmap visual DPI verification. State skipped tests, blockers and scope of evidence explicitly. Re-run relevant private integration only with authorized access; never copy private features into public tests.

Inspect the full staged diff for credentials/raw data/large artifacts before descriptive grouped commits. Push without force, update the appropriate PR, verify remote SHA and current-head CI, and keep unverified changes unmerged. Do not assign a release tag merely because CI passes. Update the master handoff and risk register with verified facts, proposals and unresolved questions distinguished. Resolve the exact final checkout commit using `git rev-parse HEAD`; documents cannot contain their own future Git hash.

Session closeout is a checkpoint, not authorization for more feature work. Future work should start with one of the documented issue acceptance criteria.
