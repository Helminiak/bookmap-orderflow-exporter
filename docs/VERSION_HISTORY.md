> Current correction: initial tab candidate fb77a37 was rejected in Bookmap. See [host/shutdown repair](BOOKMAP_HOST_REPAIR_2026-10-08.md) for reproduced cause, 18 Java tests and corrected candidate; native acceptance remains pending. Earlier implementation/acceptance statements below are historical.

> Latest authorized UI: one Orderflow exporter host with Configuration/Live status tabs; Configuration selected first, scrollable contents and fixed action buttons. Earlier two-panel/rollback descriptions below are historical anchors. See [UI follow-up](UI_TAB_FOLLOWUP_2026-10-08.md) for current build, 15 Java tests and native morning acceptance still pending.

# Version history reconstructed from Git

No Git tags or GitHub Releases existed at the 2026-10-08 audit. Software version 0.5.0 is a candidate on feature/linux-live-bridge, not a published release. Main is 2e0c0c79df9801a68ff9c3f3332a2ffff558ee2d, the validated 0.4.0 baseline. Branch names/ZIP names are not releases.

| Stage | Source/build anchors | Integration/evidence |
|---|---|---|
| v0.1 | 75b9c6c documentation; 0238774 source/validator, 2026-10-07 | Initial MBO/trades, TimeListener, seq, strict bounded queue, GZIP NDJSON/summary. First 519,012-record replay/live evidence in validation/2026-10-08-v0.1-first-runtime-capture.md. Initial file-growth buffering limitation. |
| v0.2 | c60924d UI, bc2f08e version, 06ed289 periodic sync-flush | PR #2 merged e402011. Original API 7.6.0.20, 1s checkpoints, configurable UI. Early CI 37718803627 looked for the v0.1 JAR after the bump; corrected by be7485e. |
| v0.3 | c39b851 hybrid time/size, a240f3e version | PR #3 merged 5b9a5ff. 5s/4 MiB uncompressed-payload triggers, 4 MiB buffering. Historical account in CHANGELOG. No independent sustained-load certificate recovered. |
| v0.4 | 53e3a8f capacity/checkpoint behavior, 78c70eb version | PR #4 merged 8eba0d4. 60s soft checkpoints, compressed buffer-full writes, application I/O counters. PR #5 merged 2e0c0c7 after 5700786 validation of 786,347 records, 8,451 final orders. Approval is capture-specific. |
| v0.5 candidate | f7993f6 bridge/event fan-out; 3d83403 integrity/failure tests | PR #6 open draft. Default bridge off, bounded responsive LIVE journal/bridge, ACK protocol, generic health. Private receiver is a separate unmerged PR. |
| v0.5 lifecycle/recovery | 2c5543d port release/LAN discovery; 263eba2 send retention; 80ca937 history pacing/path defaults; 646eeea identity handover | Same-owner replay and zero-event receiver recovery; no durable replay spool or book snapshot. |
| v0.5 UI/tool evolution | a900dc9 scrolling rows; 2c5543d internal tabs; e8c856c larger tabs/Java lookup; 3a3a794 original layout rollback | Preserve these historical commits. Do not reinstall an older entire runtime to revert just a UI. |
| v0.5 localized UI correction | d447a6905deec882f67ab7addc8d65ff0a00fa81 | Original two panels retained. Only network row receives measured wrapping height; other rows preserve equal baseline and 4px gaps, vertical fallback scrolling, Apply fixed. 13 Java tests/Windows+Ubuntu CI pass. Actual Bookmap DPI inspection pending. |

PRs: [#2](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/2), [#3](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/3), [#4](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/4), [#5](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/5), [#6](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/6). Governance PR #1 remains separate/unmerged. Full exact hashes and chronology: `git log --all --date=iso --decorate`. All cited prefixes resolve in the preserved repository.
