# Bookmap Orderflow Exporter v0.1

Purpose: prove that Bookmap can be used as the single decoding layer for both historical `.bmf` replay and live Rithmic MBO/trades, while emitting a normalized raw stream for the Orderflow project.

## Scope of v0.1

This is deliberately **not** an entry model, feature engine, network bridge, or trading strategy. It only captures raw MBO and trades from Bookmap.

Listeners used:

- `MarketByOrderDepthDataListener`
- `TradeDataListener`
- `TimeListener`
- `HistoricalModeListener`

Bookmap's Simplified API preserves callback order. `TimeListener` supplies the market/replay nanosecond clock associated with subsequent events. MBO callbacks provide per-order send/replace/cancel events where the data provider/replay contains MBO.

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
build\libs\bookmap-orderflow-exporter-v0.1.jar
```

The project pins Bookmap API `7.6.0.20`, matching the current official DemoStrategies build configuration used when this scaffold was created. The Bookmap API is versioned/back-compatible; if your installed Bookmap requires a different API artifact, change `bookmapApiVersion` in `gradle.properties`.

## Load into Bookmap

1. Build the JAR.
2. In Bookmap, open API plugin/add-on configuration.
3. Add `bookmap-orderflow-exporter-v0.1.jar`.
4. Open one ES instrument or a `.bmf` replay.
5. Enable **Orderflow Raw Exporter v0.1** for that instrument.
6. Let the replay run monotonically for the initial test. Do not scrub backward during the validation run.
7. Disable the addon or close the instrument to flush the output and create the summary file.

## Output directory

Default:

```text
%USERPROFILE%\BookmapOrderflowExports
```

Override before launching Bookmap:

```powershell
$env:ORDERFLOW_EXPORT_DIR='D:\Orderflow\BookmapExports'
$env:ORDERFLOW_RUN_TAG='ES historical validation 01'
```

Optional queue size:

```powershell
$env:ORDERFLOW_EXPORT_QUEUE='1000000'
```

v0.1 uses **strict backpressure**: when the writer queue fills, the Bookmap callback blocks rather than silently dropping market events. This is intentional for historical extraction correctness. We will not use this exact writer path for low-latency live production streaming.

## Output files

Each run creates:

```text
<alias>_<UTC timestamp>.ndjson.gz
<alias>_<UTC timestamp>.summary.json
```

The summary contains event totals and reconstruction anomalies.

## Validate after a replay

On Linux or Windows with Python 3:

```bash
python tools/validate_export.py path/to/export.ndjson.gz
```

For the first clean test, require:

- `parse_errors = 0`
- `seq_gaps = 0`
- no unexpected `time_reversals_observed`
- inspect `unknown_replaces` / `unknown_cancels`

Unknown replace/cancel events are not automatically proof of corrupted BMF data; they can also indicate initial-state semantics around attachment. We need to observe Bookmap's real replay behavior before deciding how to normalize those cases.

## First validation experiment

Use one modest ES `.bmf` file and record:

1. BMF filename and original trading date.
2. Contract alias displayed by Bookmap.
3. Export summary counts.
4. Validator output.
5. Whether trade events contain `aggressor_order_id` and `passive_order_id`.
6. Whether MBO order IDs remain internally consistent over the session.
7. Whether replaying the same BMF twice produces identical market-event payloads (ignoring output filenames and module-control records).

Do not begin feature engineering until this raw-data equivalence layer is verified.
