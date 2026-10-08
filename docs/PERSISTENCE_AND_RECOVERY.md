# Persistence, durability and recovery

Pipeline: callback → bounded CanonicalEvent queue → BufferedWriter (4 MiB characters, roughly 8 MiB char array) → UTF-8 encoder → GZIPOutputStream (4 MiB input buffer, sync-flush enabled) → BufferedOutputStream (4 MiB compressed bytes) → counting file stream → OS cache → storage. Compression and file I/O run only on the writer. High-volume buffer-full drains and the configurable 5–300s checkpoint (60s default) are independent triggers; approximate uncompressed payload bytes are diagnostic, not the v0.4+ flush trigger.

| State | Meaning |
|---|---|
| Received / total_records | Callback/enqueue sequence accounting, not necessarily complete delivery on failure |
| Queue buffered | In-process memory; crash loses it |
| records_persisted | Writer has passed the record to BufferedWriter; may still reside in Java/GZIP buffers |
| application_disk_bytes/write_ops | Bytes/calls handed below the Java compressed buffer to the OS, not physical NAND writes |
| ACKed | Private receiver says it validated events in process; not a durable receiver journal guarantee |
| Finalized + validated | Clean logical archive with GZIP trailer, STOP, correct schema/book and summary; no fsync guarantee |

No FileChannel.force/fsync is used. Checkpoint flush is not durable sync, and a finalized file may still be in OS/device caches. Do not infer durable latency or physical write amplification from application counters.

HISTORY journal offers wait for capacity and check writer error/interruption; wait is not time-bounded while a live writer is merely slow. Responsive LIVE defaults true: full queue or writer error marks archive invalid and later drops are observable, instead of waiting. Turning that setting off permits LIVE blocking. Worker exceptions are stored; consumers must not accept writer_ok false or incomplete summaries.

Clean stop emits STOP when applicable, attempts bounded poison enqueue/30s join, closes writer layers/finalizes GZIP, writes summary, with bridge close in finally (one-second ACK grace and 1.5s join). There is no periodic finalized summary. Exceptional stop/summary paths now execute bridge cleanup in finally; summary IOException/rebind is tested, broader disk-stall/interruption coverage remains open (issue #8); summary's bridge snapshot precedes final ACK grace and cannot prove final delivery.

Crash/disk full/forced kill may leave a truncated gzip, missing trailer/STOP/summary or invalid partial stream. Preserve the original for forensics, validate to EOF including CRC/ISIZE, and never certify an incomplete prefix as a full session. No automated archive repair or exactly-once cross-process resume is implemented. Recover by a new exporter START/book population; keep sessions separate and retain source/build provenance. Disk headroom and licensing remain operator responsibilities.

Lifecycle STOP uses non-waiting bridge offer even in HISTORY to avoid a ten-second capacity wait during disable. Full retention still invalidates/counts loss; normal HISTORY event backpressure is unchanged.

Bridge worker shutdown wakes its short park with unpark rather than interrupt, so JeroMQ context termination can complete before port reuse. CI exposed an intermittent health-port rebind failure with interruption; a five-cycle immediate reuse regression guards the correction.
