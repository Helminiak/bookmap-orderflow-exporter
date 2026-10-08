# Changelog

This project keeps the acquisition layer public and versioned so the development path is visible from the original exporter scaffold through later validation-driven changes. Proprietary entry-quality logic belongs in the separate private `orderflow-entry-engine` repository and is intentionally excluded here.

## [0.5.0] - 2026-10-08 (local bridge candidate)

- Integrated an optional JeroMQ ROUTER/DEALER bridge into the existing addon; disabled by default.
- Canonical callback copies fan out independently to journal/publisher workers; JSON and UI formatting run off market callbacks.
- Bounded non-waiting LIVE journal/bridge queues. Overflow invalidates the affected output; historical extraction retains strict journal backpressure.
- Added cumulative ACKs, retained unacknowledged events, same-receiver reconnect, explicit restart/overflow invalidation and a health channel.
- Added bridge settings/status, lightweight callback latency histograms, rates and queue counters.
- Bundled JeroMQ plus its Java dependency and license notices in the single v0.5 JAR.
- Added synthetic callback/journal/wire regression tests and loopback/stress fixtures. No proprietary model code or raw market data.
- This supersedes the feature hold following the owner's explicit bridge request. Physical Bookmap/LAN responsiveness is not yet validated.

## v0.4 runtime validation follow-up - 2026-10-08

- Approved the tested v0.4 capture/export workflow after independent validation of the complete 786,347-record archive and matching summary; see [scope and evidence](docs/validation/2026-10-08-v0.4-runtime-validation.md).
- Added strict schema/lifecycle/timestamp/MBO/execution-marker checks to the public validator and optional `--summary` checks for writer status, counts, pips and v0.4 finalized byte accounting.
- Corrected validation exit status so inconsistent MBO state/time reversals do not receive a successful result.
- Added synthetic regression tests to Windows/Ubuntu build CI. No raw market data or proprietary model logic is committed.
- v0.5 feature work is on hold pending the project owner's updates. Java recorder source and version remain v0.4.0.

## [0.4.0] - 2026-10-08

### Changed

- Replaced v0.3's explicit uncompressed-payload size flush with a **capacity-driven buffered recorder**.
- Default maximum checkpoint interval is now **60,000 ms** (60 seconds), configurable from 5 seconds to 5 minutes.
- Retained a 4 MiB compressed-output `BufferedOutputStream`. It writes to the operating system automatically when full, without waiting for the checkpoint timer.
- The periodic timer is now a safety/checkpoint flush for residual data rather than the primary high-volume write mechanism.
- Added counters for bytes and write calls handed by this process to the operating system below the 4 MiB disk buffer.
- Renamed UI terminology from generic flush timing to **checkpoint interval** to distinguish it from automatic buffer-full writes.

### Why this is closer to Bookmap

Bookmap's public `BookmapRecorderDemo` states that the recorder keeps **internal buffers** and requires `recorder.fini()` at the end so those buffers are written to the file. The public demo does not expose or document an exact internal byte threshold or periodic flush interval.

v0.4 therefore copies the observable architecture rather than inventing a Bookmap constant:

1. accumulate records in memory,
2. compress continuously,
3. allow the output buffer to drain automatically when it fills,
4. perform a much less frequent soft checkpoint,
5. finalize all remaining buffers on clean stop.

This also matches the historical behavior observed by the project owner on mechanical disks: sparse writes in quiet markets and more continuous disk activity when market data volume was high.

### Defaults

- writer queue: 1,000,000 records
- compressed-output disk buffer: 4 MiB
- maximum checkpoint interval: 60 seconds
- explicit payload-size flush trigger: disabled
- forced durable `fsync` per checkpoint: none

### Important interpretation

`application_disk_bytes` and `application_disk_write_ops` measure bytes/write calls that the Java process hands to the operating system below the application buffer. They are **not** physical NAND-write measurements. Windows and the SSD controller may still cache, merge, reorder, or coalesce those writes.

### Validation target

Run v0.4 at 60 seconds through both quiet and heavy ES periods and verify:

- quiet periods show few application write operations between checkpoints,
- heavy periods naturally increase write operations as the 4 MiB output buffer fills,
- the writer queue remains near zero,
- persisted/enqueued record counts remain equal,
- no sequence gaps or writer errors appear,
- shutdown finalizes the GZIP stream and summary cleanly.

## [0.3.0] - 2026-10-08

### Changed

- Replaced the fixed 1-second-only flush policy with a hybrid **time OR size** policy.
- Default maximum flush interval is now 5,000 ms.
- Default flush threshold is 4 MiB of approximately accumulated uncompressed NDJSON payload.
- Increased the Java/GZIP/file buffering layer to 4 MiB.
- The UI now exposes both the maximum flush interval and the size threshold.
- Live status now shows buffered bytes since the last flush, flush reason, and flush count.
- The status layout was made more vertical so high-volume counters do not clip at the right edge.

### Rationale

Bookmap's public recorder example explicitly states that its recorder uses internal buffers that are written/finalized when recording is finished, but Bookmap does not publish a fixed recorder flush interval or byte threshold. v0.3 therefore copies the **buffered-recorder architecture**, not an undocumented magic number.

The hybrid policy limits idle-period buffering with a time bound while allowing high-volume periods to flush by accumulated payload size. Java `flush()` is not treated as an `fsync`; Windows and the storage controller remain free to coalesce physical writes.

### Defaults

- writer queue: 1,000,000 records
- Java/GZIP/file buffer: 4 MiB
- maximum flush interval: 5 seconds
- flush threshold: 4 MiB
- forced durable sync per flush: none

### Validation target

Run v0.3 through a high-volume ES period and verify:

- queue remains near zero,
- size-triggered flushes occur during bursts,
- time-triggered flushes occur during quieter periods,
- file size grows continuously,
- no sequence gaps or writer errors appear,
- stop/unload still finalizes the stream and summary cleanly.

## [0.2.0] - 2026-10-08

### Added

- Bookmap-native configuration panel.
- Bookmap-native live status panel.
- Configurable output directory and run tag.
- Configurable MBO and trade export toggles.
- Configurable writer queue capacity.
- Configurable disk flush interval, defaulting to 1000 ms.
- Live counters for MBO adds, replaces, cancels, trades, open tracked orders, queue utilization, persisted records, disk size, last flush age, integrity counters, and most recent MBO/trade event.
- Windows and Ubuntu GitHub Actions build/package verification.
- Build artifact verification that the expected Bookmap addon class is present in the JAR.

### Fixed

- v0.1 appeared to stop writing because the NDJSON stream was buffered behind the BufferedWriter, GZIP stream, and buffered file output. Data was actually accumulating in process and became visible on disk when the addon was unloaded and the stream was closed.
- The writer now performs periodic synchronous flushes so the `.ndjson.gz` file grows while replay/live capture is active.
- GZIP sync-flush support is enabled so periodic flushes propagate through the compression layer instead of remaining buffered until shutdown.

### Validated

- The first real Bookmap runtime capture completed cleanly with 519,012 records.
- Historical Bookmap replay exposed individual MBO add/replace/cancel events with stable order IDs.
- The same exporter transitioned from historical replay into live Rithmic data using the `REALTIME_START` callback and continued writing the same normalized stream.
- The first captured stream had zero sequence gaps, zero timestamp reversals, zero duplicate adds, zero unknown replaces, and zero unknown cancels.
- Trade callbacks preserved aggressor and passive order IDs on the captured non-zero fills.
- Detailed results are documented in `docs/validation/2026-10-08-v0.1-first-runtime-capture.md`.

### Known semantics to refine next

- The first replay timestamp contained 6,721 MBO adds at one timestamp. Treat this as initial-book population until a dedicated initial-state marker is added.
- Bookmap trade callbacks include zero-size execution-end boundary records. The raw stream preserves these records; downstream normalization should distinguish fill records from execution boundaries rather than discarding them.
- Runtime validation of the new v0.2 Bookmap UI and periodic flush behavior is still pending even though the v0.2 source builds successfully against Bookmap API 7.6.0.20 on Windows and Ubuntu.

## [0.1.0] - 2026-10-07

### Added

- Initial Bookmap Simplified API addon scaffold.
- `MarketByOrderDepthDataListener` support for MBO add/replace/cancel callbacks.
- `TradeDataListener` support with aggressor/passive order IDs when supplied by Bookmap.
- `TimeListener` market/replay nanosecond timestamps.
- `HistoricalModeListener` transition marker from historical catch-up to realtime.
- Strict bounded writer queue with backpressure instead of silent event dropping.
- GZIP-compressed NDJSON event output.
- End-of-run JSON summary.
- Python export validator.
- Public schema documentation.
- Separation between this public acquisition/normalization repository and the private entry engine.

### Initial limitation discovered during first runtime test

- The exporter opened and captured correctly, but the output file did not visibly grow while running because data remained buffered until shutdown. Unloading the addon flushed the data and generated the summary, which led directly to the v0.2 periodic-flush change.
