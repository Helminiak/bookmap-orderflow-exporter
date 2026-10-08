# Bookmap Orderflow Exporter

Public acquisition and canonical MBO/trade normalization for Bookmap BMF replay and provider LIVE data. It is not a trading model or execution system. Private entry-quality research and licensed raw market history stay outside this repository.

## Current state

- Known validated main baseline: **v0.4.0**, main `2e0c0c79df9801a68ff9c3f3332a2ffff558ee2d`; 786,347-record capture validation is documented, not a universal completeness certificate.
- Development: **v0.5a UI preview / 0.5.0 candidate**, `feature/linux-live-bridge`, [draft PR #6](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/6). Optional single-JAR ACKed bridge, responsive LIVE journal, measured network-row height and a single panel with Configuration/Live status tabs are implemented. No merge, release or tag is implied.
- Windows/Ubuntu source, packaging, validator and launcher CI pass on the localized UI source anchor d447a69. Current-head closeout CI/state is reported in PR #6 and [master handoff](HANDOFF_MASTER.md).
- A supplied physical 60-second smoke report inspected earlier passed for 4,656 new events; separate 57,771-event PASS is owner-reported. Actual Bookmap UI at Windows display DPI, sustained load, phase-separated slowdown and exceptional failure acceptance remain open.

See [whole-page v0.5a follow-up](docs/UI_V05A_2026-10-08.md) for the latest UI preview. The owner confirmed the preceding repair can be disabled, with a brief pause; this does not certify the new UI. The owner rejected the initial tab build fb77a37 in Bookmap. See [blank-configuration/shutdown repair](docs/BOOKMAP_HOST_REPAIR_2026-10-08.md) for the corrected candidate and acceptance limits; [earlier tab follow-up](docs/UI_TAB_FOLLOWUP_2026-10-08.md) is historical.

Start with [HANDOFF_MASTER.md](HANDOFF_MASTER.md), [AGENTS.md](AGENTS.md), [risk register](docs/KNOWN_ISSUES_AND_RISKS.md) and [session closeout](docs/SESSION_CLOSEOUT_2026-10-08.md).

## Build, install and validate

JDK 17, Gradle 8.10 (installed separately; no wrapper), Python 3.12. Maven network access needed initially. API core/simplified pinned to Bookmap 7.6.0.20. Runtime Java 17+; JeroMQ 0.6.0 is bundled with license notices, Bookmap API classes are compile-only.

```sh
gradle --no-daemon clean test jar writeFixtureClasspath smokeTestBundle
python -m unittest discover -s tests -v
python tools/validate_export.py CAPTURE.ndjson.gz --summary CAPTURE.summary.json
```

Outputs: `build/libs/bookmap-orderflow-exporter-v0.5a.jar`, `build/distributions/orderflow-v0.5a-windows-smoke-test.zip`. Close Bookmap before replacing the JAR; install only one exporter addon. Enable **Orderflow Raw Exporter v0.5a** for an instrument. One **Orderflow exporter** panel contains **Configuration** (opens first), **Live status** and **Information**. Bookmap's outer scrollbar scrolls the entire Configuration page, including Apply at the bottom; there is no smaller nested settings viewport. Network controls keep measured wrapping. Status diagnostics retain their own scrolling. Information provides the preview label and original help/support content. Apply saves settings/reloads and begins a new file/session. A short disable pause can occur during archive finalization and bridge cleanup. UI revision 0.5a does not change the canonical schema, transport or software metadata version 0.5.0.

The ZIP contains JAR, BAT, PS1 and README. Keep them together and run BAT on Windows, not PS1 double-click. It discovers Java/LAN health and reports PASS/FAIL/INCONCLUSIVE without registering a second market receiver. The private receiver must be started before opening/enabling a fresh Bookmap bridge session. Full setup/recovery is in [LINUX_BRIDGE.md](docs/LINUX_BRIDGE.md) and [testing/troubleshooting](docs/TESTING_AND_TROUBLESHOOTING.md).

## Actual defaults

| Setting | Default / constraint |
|---|---|
| MBO and trades | Both true |
| Output / run tag | Blank; output resolves ORDERFLOW_EXPORT_DIR then user-home BookmapOrderflowExports; tag may use ORDERFLOW_RUN_TAG |
| Writer queue | 1,000,000 events; configurable 10,000–5,000,000 |
| Maximum checkpoint | 60,000ms; configurable 5,000–300,000ms |
| Compressed-output buffer | Fixed 4 MiB; writer/GZIP have additional arrays |
| Responsive LIVE journal | true; overload marks archive INVALID rather than waiting |
| Bridge | Disabled; bind 0.0.0.0, data 5555, health 5556, unacknowledged capacity 100,000 |

HISTORY extraction intentionally applies writer backpressure; enabled historical bridge can wait up to ten seconds on stalled ACK capacity/drain. Responsive LIVE offers do not wait for optional transport. Strict LIVE journal mode permits callback waiting. Flush is not fsync; counters describe buffered/application/OS stages, not durable storage. INVALID streams must not feed inference or be certified complete. Distinct instruments need unique enabled port pairs, and transport is trusted-LAN-only without auth/TLS.

Output is `<alias>_<UTC extraction timestamp>.ndjson.gz` plus clean-stop summary. Read the entire archive for CRC/ISIZE, strict seq/schema/lifecycle/time/MBO validation and matching summary. Timestamp units are nanoseconds but precision comes from Bookmap/provider; repeated timestamps are valid and seq orders events. Provider completeness and initial-book semantics need separate evidence.

## Engineering map

- [Architecture](docs/ARCHITECTURE.md), [pipeline/lineage](docs/DATA_PIPELINE.md), [schema](docs/EVENT_SCHEMA.md), [original field reference](SCHEMA.md)
- [Persistence/recovery](docs/PERSISTENCE_AND_RECOVERY.md), [failure modes](docs/RELIABILITY_AND_FAILURE_MODES.md), [performance/reproduction](docs/PERFORMANCE.md)
- [Version history](docs/VERSION_HISTORY.md), [CHANGELOG](CHANGELOG.md), [decisions](docs/ENGINEERING_DECISIONS.md)
- [Historical research roadmap](docs/HISTORICAL_REPLAY_AND_RESEARCH.md), [vision](docs/PROJECT_VISION.md), [roadmap](docs/ROADMAP.md), [public/private boundary](docs/PUBLIC_PRIVATE_BOUNDARIES.md)

No bulk BMF/NDJSON/Parquet corpora, private features, thresholds, model weights or credentials belong in public Git. Tiny explicitly synthetic fixtures, generic source and sanitized aggregate evidence are appropriate. See AGENTS.md before future edits.
