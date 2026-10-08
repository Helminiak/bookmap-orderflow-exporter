# Bookmap Orderflow Exporter

Purpose: use Bookmap as the decoding layer for historical `.bmf` replay and live market data while emitting a normalized raw event stream for downstream Orderflow research.

## Repository boundary

This repository is the **data-acquisition and normalization layer**.

It answers:

> What market events did Bookmap receive and in what sequence?

It is deliberately **not** the proprietary entry-quality model, feature engine, trade-management engine, or execution strategy.

The downstream private repository is intended to consume the normalized output of this project for feature engineering, auction-state analysis, MBO microstructure analysis, replay research, and entry-quality scoring.

## v0.2 Bookmap UI

v0.2 adds a Bookmap-native configuration and status interface. After enabling **Orderflow Raw Exporter v0.2** for an instrument, open its settings/configuration panel.

Configurable items:

- output directory
- run tag
- writer queue capacity
- export MBO records on/off
- export trade records on/off

Changing configuration and pressing **Apply settings / restart exporter** stores the settings in Bookmap, reloads the addon for that instrument, and starts a new output file.

The live status panel shows:

- current instrument
- HISTORY vs REALTIME phase
- Bookmap market/replay timestamp
- active output file
- writer queue utilization
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
gradle clean jar
```

Output:

```text
build\libs\bookmap-orderflow-exporter-v0.2.jar
```

The project currently pins Bookmap API `7.6.0.20`, matching the official DemoStrategies build configuration used when the initial scaffold was created. If the installed Bookmap release requires another compatible API artifact, change `bookmapApiVersion` in `gradle.properties`.

## Load into Bookmap

1. Build or download the JAR.
2. In Bookmap, open API plugin/add-on configuration.
3. Add `bookmap-orderflow-exporter-v0.2.jar`.
4. Open one ES instrument or a `.bmf` replay.
5. Enable **Orderflow Raw Exporter v0.2** for that instrument.
6. Open the addon's settings panel to view/configure the exporter.
7. For initial validation, replay only a few minutes monotonically.
8. Disable the addon or close the instrument to flush output and create the summary file.

## Output directory

The output directory is now configurable inside Bookmap.

If the Bookmap setting is blank, the addon falls back to:

1. `ORDERFLOW_EXPORT_DIR`, if present.
2. Otherwise `%USERPROFILE%\BookmapOrderflowExports`.

The run tag is also configurable in Bookmap. If blank, `ORDERFLOW_RUN_TAG` is used when present.

The historical extraction path uses strict backpressure: when the writer queue fills, the Bookmap callback blocks rather than silently dropping market events. This is intentional for historical extraction correctness. A production low-latency live-stream path may use a different transport design.

## Output files

Each run creates files similar to:

```text
<alias>_<UTC timestamp>.ndjson.gz
<alias>_<UTC timestamp>.summary.json
```

The summary contains event totals, active export configuration, and reconstruction anomalies.

## Validate after a replay

```bash
python tools/validate_export.py path/to/export.ndjson.gz
```

For a clean validation test require:

- `parse_errors = 0`
- `seq_gaps = 0`
- no unexpected `time_reversals_observed`
- inspection of `unknown_replaces` / `unknown_cancels`

Unknown replace/cancel events are not automatically proof of corrupt BMF data; they can also indicate initial-state semantics around attachment. Bookmap replay behavior must be observed before those cases are normalized away.

## First validation experiment

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
