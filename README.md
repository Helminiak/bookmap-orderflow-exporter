# Bookmap Orderflow Exporter

Purpose: use Bookmap as the decoding layer for historical `.bmf` replay and live market data while emitting a normalized raw event stream for downstream Orderflow research.

## Project status

- **v0.1 runtime validated:** first real Bookmap replay/live capture completed with 519,012 ordered records and a clean writer summary.
- **v0.2 build verified:** Windows and Ubuntu CI compile/package successfully against Bookmap API `7.6.0.20`.
- **v0.2 runtime validated:** native settings/status UI and periodic disk flushing were verified in Bookmap with the writer queue at 0% during the observed run.
- **v0.3 validated at build/runtime level:** established the configurable buffering/status framework.
- **v0.4 runtime approved:** the complete 786,347-record capture passes independent stream/summary validation; see [approval and scope](docs/validation/2026-10-08-v0.4-runtime-validation.md). It uses a 4 MiB capacity-driven compressed-output buffer plus a 60-second maximum checkpoint interval, closer to Bookmap's publicly documented buffered-recorder behavior.
- Development history is intentionally preserved. See [CHANGELOG.md](CHANGELOG.md) and [the first runtime validation record](docs/validation/2026-10-08-v0.1-first-runtime-capture.md).


## Next release

**v0.5 optional live bridge is implemented for local testing.** One addon fans the same canonical events to the journal and an acknowledged ZeroMQ receiver. See [protocol, defaults, limitations, and two-machine setup](docs/live-bridge-v0.5.md) and [local test evidence](docs/validation/2026-10-08-v0.5-loopback.md). The v0.4 approval remains limited to its tested capture. v0.5 Bookmap runtime/LAN approval is pending.

## Repository boundary

This repository is the **data-acquisition and normalization layer**.

It answers:

> What market events did Bookmap receive and in what sequence?

It is deliberately **not** the proprietary entry-quality model, feature engine, trade-management engine, or execution strategy.

The downstream private repository is intended to consume the normalized output of this project for feature engineering, auction-state analysis, MBO microstructure analysis, replay research, and entry-quality scoring.

## v0.4 Bookmap UI and buffering

v0.4 keeps the Bookmap-native configuration/status interface but changes disk behavior from an explicit payload threshold to a capacity-driven recorder. After enabling **Orderflow Raw Exporter v0.4** for an instrument, open its settings/configuration panel.

Configurable items:

- output directory
- run tag
- writer queue capacity
- maximum checkpoint interval (5,000-300,000 ms; default 60,000 ms)
- fixed 4 MiB compressed-output disk buffer (automatically drains when full)
- export MBO records on/off
- export trade records on/off

Changing configuration and pressing **Apply settings / restart exporter** stores the settings in Bookmap, reloads the addon for that instrument, and starts a new output file.

The live status panel shows:

- current instrument
- HISTORY vs REALTIME phase
- Bookmap market/replay timestamp
- active output file
- writer queue utilization
- records persisted to the writer
- current on-disk file size
- approximate payload accumulated since the previous checkpoint
- application bytes/write calls handed to the operating system below the 4 MiB buffer
- last checkpoint age and checkpoint count
- MBO add/replace/cancel counts
- trade count
- number of tracked MBO orders
- records written/enqueued
- integrity/anomaly counters
- last MBO event
- last trade
- writer errors, if any

The panel also includes **Open export folder** and **Refresh** controls.

## Scope

Appropriate contents include:

- Bookmap add-on/plugin source
- MBO listeners and normalization code
- trade-event listeners
- timestamp normalization
- order-book reconstruction support
- raw-event schemas
- deterministic replay tests
- export validators
- test fixtures small enough for source control
- build tooling and dependency definitions
- public documentation of the export format

## Current implementation

Listeners used:

- `MarketByOrderDepthDataListener`
- `TradeDataListener`
- `TimeListener`
- `HistoricalModeListener`

Bookmap's Simplified API preserves callback order. `TimeListener` supplies the market/replay nanosecond clock associated with subsequent events. MBO callbacks provide per-order send/replace/cancel events where the data provider or replay contains MBO.

## Build

Requirements:

- JDK 17
- Gradle
- Internet access to Bookmap's Maven repository on the build PC

From this directory:

```powershell
gradle clean test jar
```

Output:

```text
build\libs\bookmap-orderflow-exporter-v0.5.jar
```

The project currently pins Bookmap API `7.6.0.20`, matching the official DemoStrategies build configuration used when the initial scaffold was created. If the installed Bookmap release requires another compatible API artifact, change `bookmapApiVersion` in `gradle.properties`.

## Load into Bookmap

1. Build or download the JAR.
2. In Bookmap, open API plugin/add-on configuration.
3. Add `bookmap-orderflow-exporter-v0.5.jar`.
4. Open one ES instrument or a `.bmf` replay.
5. Enable **Orderflow Raw Exporter v0.5** for that instrument.
6. Open the addon's settings panel to view/configure the exporter.
7. For initial validation, replay only a few minutes monotonically.
8. Disable the addon or close the instrument to flush output and create the summary file.

## Output directory

The output directory is now configurable inside Bookmap.

If the Bookmap setting is blank, the addon falls back to:

1. `ORDERFLOW_EXPORT_DIR`, if present.
2. Otherwise `%USERPROFILE%\BookmapOrderflowExports`.

The run tag is also configurable in Bookmap. If blank, `ORDERFLOW_RUN_TAG` is used when present.

The historical extraction path uses strict backpressure: when the writer queue fills, the Bookmap callback blocks rather than silently dropping market events. This is intentional for historical extraction correctness. v0.5 uses independent non-waiting LIVE bridge/journal buffers; overflow explicitly invalidates the affected output. See the v0.5 protocol/setup guide.

v0.4 uses a capacity-driven buffered-recorder policy. A 4 MiB `BufferedOutputStream` sits below GZIP. During high data volume it writes to the operating system automatically when that compressed-output buffer fills; during quiet periods a configurable maximum checkpoint interval flushes residual buffered data. The default checkpoint interval is 60 seconds. No checkpoint calls `fsync`, so Windows and the SSD controller can still coalesce physical writes.

Bookmap's public Recorder API demo states that its recorder keeps internal buffers and requires `recorder.fini()` to write those buffers when recording ends. Bookmap does not publicly document an exact internal write-buffer size or periodic flush interval. This project therefore copies that buffered-recorder architecture—not an undocumented Bookmap constant—and makes our own application-level write behavior observable in the status panel.

## Output files

Each run creates files similar to:

```text
<alias>_<UTC timestamp>.ndjson.gz
<alias>_<UTC timestamp>.summary.json
```

The summary contains event totals, active export configuration, and reconstruction anomalies.

## Validate after a replay

```bash
python tools/validate_export.py path/to/export.ndjson.gz --summary path/to/export.summary.json
```

The validator fails with exit code 2 on stream/schema/lifecycle/MBO inconsistencies, gzip integrity failures, or summary mismatches. With a summary it also validates pips and v0.4 finalized byte accounting. Capture-only validation remains available, with summary/pips checks explicitly marked as not performed.

For a clean validation test require:

- `parse_errors = 0`
- `seq_gaps = 0`
- `time_reversals_observed = 0`
- `duplicate_adds_reconstructed = 0`
- `unknown_replaces_reconstructed = 0` / `unknown_cancels_reconstructed = 0`
- `valid = true` and `summary_checked = true` when a summary is supplied

Unknown replace/cancel events are not automatically proof of corrupt BMF data; they can also indicate initial-state semantics around attachment. Bookmap replay behavior must be observed before those cases are normalized away.

## Validation history

The first completed runtime capture is documented in [`docs/validation/2026-10-08-v0.1-first-runtime-capture.md`](docs/validation/2026-10-08-v0.1-first-runtime-capture.md). It demonstrated coherent historical MBO reconstruction and continuity into live Rithmic callbacks.

For subsequent validation runs, record:

Record:

1. BMF filename and original trading date.
2. Contract alias displayed by Bookmap.
3. Export summary counts.
4. Validator output.
5. Whether trade events contain aggressor and passive order IDs.
6. Whether MBO order IDs remain internally consistent over the session.
7. Whether replaying the same BMF twice produces identical market-event payloads, ignoring output filenames and module-control records.

Do not begin feature engineering until the raw-data equivalence layer is verified.

## Data policy

Do **not** commit bulk market-history data to normal Git.

Exclude:

- Bookmap `.bmf` archives
- large NDJSON/CSV/Parquet exports
- model training corpora
- generated feature stores
- model weights
- credentials or market-data-provider secrets

Commit schemas, small synthetic fixtures, manifests, hashes, extraction code, validation code, and metadata needed to reproduce datasets instead.

## Relationship to the private entry engine

Conceptually:

```text
Bookmap / Rithmic / historical BMF
              ↓
bookmap-orderflow-exporter
              ↓
normalized deterministic raw events
              ↓
private orderflow entry engine
```

Keeping this boundary clean allows the exporter to remain independently testable and suitable for public source control while proprietary trading logic remains private.
