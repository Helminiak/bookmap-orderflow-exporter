# Changelog

This project keeps the acquisition layer public and versioned so the development path is visible from the original exporter scaffold through later validation-driven changes. Proprietary entry-quality logic belongs in the separate private `orderflow-entry-engine` repository and is intentionally excluded here.

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
