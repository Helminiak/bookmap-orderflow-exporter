# Data pipeline and lineage

| Tier | Representation | Status / authority |
|---|---|---|
| 0 | Licensed Bookmap BMF/provider data | Local source, Bookmap decode required; never commit. |
| 1 | bookmap-orderflow-v0.1 NDJSON.GZ + summary | Implemented public authoritative callback archive; validate clean STOP/CRC/schema/seq/book and summary. |
| 2 | Compiled event columns / efficient binary | Private unmerged research candidate; Arrow IPC/Parquet are downstream choices, not exporter output. Preserve seq, market_ns, IDs, side/event codes and integer ticks derived using pips. |
| 3 | Causal model datasets with separately governed outcomes | Private unmerged dataset candidate/future integration; no feature or target formulas belong here. |

Acquisition record identity is (extraction/session provenance, alias, seq); seq restarts each exporter instance. Record source BMF hash/market date, alias/contract, provider, pips/multiplier, exporter commit/JAR hash, settings/filter status, extraction time, archive+summary hashes, validation results and downstream compiler version. Store only sanitized manifests in public Git. Filename timestamp is extraction UTC, not the market trading date.

Keep order within each session. Partition across independent sessions/instruments only when each has a complete initial state. Captures starting at the same market timestamp may be overlapping extractions and must not be treated as independent training examples. Validate before compiling; invalid prefixes may be diagnostic evidence but are not certified complete sessions. Do not use arrival-clock delay as market time or market_ns as a sleep instruction in offline replay.

Tier-2/Tier-3 transformations must retain provenance and be reproducible. Training splits are chronological, account for overlapping sessions and future target horizons, and keep future outcomes out of contemporaneous inputs. Research design remains private. See [historical research roadmap](HISTORICAL_REPLAY_AND_RESEARCH.md).
