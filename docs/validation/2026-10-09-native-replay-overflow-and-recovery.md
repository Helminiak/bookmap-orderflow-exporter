# Native Bookmap replay overflow and bridge recovery: owner findings

**Recorded:** 2026-10-09 UTC (2026-10-08 U.S. Eastern local time)  
**Status:** Known engineering/recovery problems; **not corrected or release-approved**.  
**Scope:** Public v0.5a Bookmap exporter, optional Windows-to-Linux bridge, and Bookmap configuration UI. Keep the approved v0.4 source and the owner-visually-accepted v0.5a review JAR unchanged. The owner has returned to a **live market**; do not reload, disable, change settings, or restart the running Bookmap/exporter to investigate these observations.

## Observed physical evidence

The owner reopened a **large locally held Bookmap `.bmf` recording**, positioned/stopped near **midday**. Bookmap preloaded all earlier recorded data to reconstruct its book, making the initial workload a potentially extreme accelerated **HISTORY catch-up**, not normal steady-state live CME throughput.

- The initial Linux receiver attempted an exporter session with a different receiver identity. The publisher returned `INVALID: receiver restarted; fresh exporter START required`. Restarting the *Bookmap market feed* did not start a fresh exporter session.
- After the owner restarted the receiver and used the exporter's explicit **Apply settings / restart exporter** action, the Linux receiver went from `DISCONNECTED` to **`HEALTHY`**, reporting **seq=3,208**, **3,207 open reconstructed orders**, **0 sequence gaps** and **0 unexpected duplicates**. That proves the initial physical Windows-to-Ubuntu handshake and bounded canonical validation/MBO reconstruction.
- The producer subsequently reported **`INVALID: outbound buffer overflow`**, while the receiver remained at seq=3,208. **This replay failed overall**: completeness beyond the last accepted record is not established and the receiver must not be treated as healthy for training, prediction, or trade decisions. The displayed ~5,773 events/s was the receiver's **running average**, not a measured peak event rate.
- After the failure, the owner found it difficult to **scroll down far enough/quickly enough to uncheck “Live Linux bridge”**. The owner had to **unload and reload** the exporter before returning to the normal live Bookmap setup. This is operator-reported difficulty; its exact UI cause has **not yet been reproduced**. The previously fixed four-line notice clipping is a distinct issue.
- The owner is **currently back on a live Bookmap market**. No change to the installed JAR, session, checkbox, broker connection, or capture is authorized by this document.

The publisher's exact queue high-water mark, ACK lag, send-failure count, Replay/Bookmap callback peak rate, archive validation, receiver hardware saturation, and slowdown were **not recorded in this observation**. These remain unknown and must not be inferred from an INVALID message.

## Source-supported mechanisms (current v0.5a candidate)

1. In `BookmapOrderflowExporter.Settings`, the bridge defaults to **disabled**, binds to **`0.0.0.0`** until configured, uses TCP market/health ports **5555/5556**, and has a default **100,000-event unacknowledged retention capacity**.
2. In `BookmapOrderflowExporter.enqueue`, the optional bridge currently receives **nonwaiting `bridge.offer(event)` for both HISTORY and REALTIME**. This is separate from the historical strict local **journal** writer. Although `LiveBridge.offerHistorical` and `finishHistorical` exist, they are not on that active enqueue path.
3. In `LiveBridge.retain`, reserved **queue + in-flight** events reaching configured capacity cause `invalidate("outbound buffer overflow")`; publisher `depth` is released after suitable cumulative receiver ACKs, not merely when a network send is attempted. On invalidation, future events cannot be silently represented as delivered.
4. The publisher binds a receiver identity to a session. A different receiver ID in that session invalidates it with `receiver restarted; fresh exporter START required`. A Bookmap feed reset is not an exporter reload, and a new receiver process cannot resume an old accepted session.
5. Receiver ACK means **validated in RAM**, **not durable storage**; the finalized local journal, its CRC, event sequence, lifecycle and summary are separately authoritative for recoverable offline history.

The observed overflow is **consistent with replay producing events faster than the bounded publisher/receiver/ACK pipeline can release them**. That does **not yet identify the limiting component**: Windows publisher scheduling/serialization, the TCP stack, receiver parsing/MBO reconstruction, acknowledgement scheduling, or the replay engine itself.

## Candidate engineering approaches (compare before implementing)

### A. Accelerated history through the journal/offline-replay pipeline — preferred to evaluate first

Use the validated, finalized Tier-1 local **NDJSON.GZ journal** as a durable checkpoint, then deliver/replay it to the Linux ingest process as quickly as the downstream machine can validate, without limiting Bookmap callbacks on the LAN connection. The archive must actually pass gzip CRC/ISIZE, strict sequence/count/START/STOP and summary checks **before** it is considered complete. Make provenance/source revisions, offsets/checkpoints and failure/restart semantics explicit. This route needs performance measurements, disk-space budgets, recovery design, and safeguards against duplicate/missing history.

This separates **Bookmap historical extraction speed** from **LAN receiver throughput** and is compatible with future accelerated model-training replay. It does not replace the low-latency live transport.

### B. HISTORY-only bounded backpressure/pacing

Evaluate whether the existing `offerHistorical` / `finishHistorical` mechanisms or bounded ACK-aware replay pacing can safely bound the historical backlog. A ten-second wait already exists in historical helper code but is **not used** on the current active bridge path. Do not simply activate it without verifying Bookmap SDK callback-thread contracts, HISTORY→REALTIME atomicity, shutdown, receiver-loss timeout, data completeness, deadlock freedom, UI responsiveness, and replay slowdown. **REALTIME callbacks must remain nonblocking** with honest explicit INVALID on unrecoverable loss.

### C. Bounded durable spool

A disk-backed publisher-side transport spool could bridge receiver speed variance and survive temporary disconnects, but it introduces disk-stall, corruption, duplicate delivery, checkpoint, shutdown, and recovery complexity. Specify contracts and test crash/forced-stop behavior before coding. Increasing the in-memory capacity alone does not make large preloads lossless.

**Required invariants:** No silent truncation; ACK never means fsync; sequence and session provenance preserved; no false HEALTHY; no unlimited RAM; no indefinite blocking of Bookmap callbacks; strict local archive validity remains independent of bridge transport failure.

## Operator recovery and UI defect

User experience observed: after bridge INVALID, the user struggled to reach the **Live Linux bridge** checkbox while scrolling. That checkbox is currently part of the scrollable Configuration form; **Apply settings / restart exporter** is a distinct explicit action and **starts a new archive/session**. The top persistent notice may explain INVALID but does not provide a fixed-position bridge-disable affordance.

Tracked separately as **[Issue #17](https://github.com/Helminiak/bookmap-orderflow-exporter/issues/17)**:

- Make **bridge status and an archive-only/disable-for-next-session control visible and keyboard accessible** without deep scrolling (e.g. pinned near status plus a fixed Apply area), including narrow native Bookmap windows and Windows 100/125/150% DPI.
- Distinguish **“Disable bridge on next Apply”** from immediate shutdown. Changes must **never silently reload/unload**, mutate saved configuration, touch broker connections, or erase the current capture. Display explicit warning that applying restarts the exporter and begins a new session.
- Document recovery in the UI: `INVALID` is not healed by resetting the feed or toggling settings; a clean receiver must be started **before** a new exporter START for the next complete bridged session. To use archive-only mode, uncheck bridge and Apply in an owner-approved safe window.
- Reproduce actual owner-reported scroll/keyboard behavior and responsiveness in native Bookmap, not only Swing preferred-size tests. Check whether large status content/EDT work is involved, but do **not** assert root cause until measured.

The separate convenience request to auto-detect the Windows-local Bind IP is **[Issue #16](https://github.com/Helminiak/bookmap-orderflow-exporter/issues/16)**. It does not address replay throughput or INVALID recovery.

## Next acceptance matrix (controlled offline Windows replay only)

1. Reproduce the large midday preload on **offline Bookmap replay**, using the actual local owner capture but keep the licensed `.bmf`, raw NDJSON.GZ, credentials, broker/account context and proprietary source **off public GitHub**.
2. Capture exact JAR source/manifest SHA, Bookmap/Java/Windows versions, replay position and speed, whether the source is HISTORY or REALTIME, timestamped publisher and receiver stats. From publisher health capture `queue_capacity`, `queue_high_water`, `queue_depth`, `overflows`, `last_offered_seq`, `last_published_seq`, `acknowledged_seq`, `unconfirmed_from_seq`, `unconfirmed_through_seq`, `send_failures`, `reconnects` and archive `writer_ok`.
3. Exercise and compare 1x, stepped accelerated replay, maximum/preload bursts and HISTORY→REALTIME transition **with fresh sessions each time**. Track Bookmap wall time/UI responsiveness and callback p95/p99 upper-bound histograms, CPU/RAM, journal throughput, network throughput, receiver queue/ACK throughput, and state. Do not use running-average events/s as a peak estimate.
4. Inject a slow or unavailable receiver, finite capacity exhaustion, brief same-process disconnect/reconnect, new receiver process, forced writer failure, abort and STOP. Assert exact INVALID/unconfirmed behavior and intact recoverable local archive when available.
5. Validate every accepted record and final book against the archive/summary and Java/Python deterministic fixtures. Prevent incomplete or invalid streams from entering model-training/entry-quality processing.
6. Validate that the **bridge-disable control is rapidly reachable** and can be safely changed under error/stress at Windows DPI 100%, 125%, 150%. Demonstrate it does not auto-reload and that outer controls remain usable.
7. Separate acceptance gates: historical archive, historical network delivery, **normal live market path**, application slowdown (<3% preferred; 3–5% review; >5% optimize; >10% unacceptable), and native UI usability. A single passing smoke test cannot certify all of them.

## Work sequencing / authority

- **Now:** maintain the owner's already restored **live market** without changing active settings or JAR. Normal live market measurement is a separate test and needs an explicitly safe window for any exporter restart.
- **Next controlled offline development:** one qualified **local Qwen coding agent** prepares a bounded design comparison, deterministic tests and sanitized performance instrumentation; independent verification remains mandatory. Cloud Codex is escalation only.
- **Later:** implement and test one chosen historical transport strategy and the separate UI recovery affordance in new review branches; preserve existing accepted artifact and approved main. No automatic merge, deployment, live brokerage interaction or Marketplace release.

Related review: [PR #6 — producer/transport](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/6), [PR #15 — owner-approved notice layout](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/15).
