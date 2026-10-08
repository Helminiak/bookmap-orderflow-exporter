# Reliability and failure modes

| Mode/failure | Implemented behavior | Remaining verification |
|---|---|---|
| HISTORY writer full | Callback waits, checking error/interruption; no silent drop | Long disk stall / cancellation responsiveness / disk-full injection |
| HISTORY bridge full/transition drain | Wait up to 10s per stalled wait; then bridge INVALID, archive independent | Provider replay/UI behavior during timeout |
| Responsive LIVE writer full/error | Writer_error invalidates archive; drops counted; callback does not wait for queue | Sustained overload/GC and actual Bookmap latency |
| Responsive LIVE bridge full | INVALID + overflow; retained backlog discarded/counted, later offers dropped | Real network outage/load/producer burst acceptance |
| LIVE strict option | Journal callback waits like HISTORY | Not suitable for responsiveness acceptance; document operator choice |
| Same owner reconnect | Retained unacknowledged replay/validated duplicate handling | Long outage consumes finite capacity; not durable recovery |
| New owner/session | Fail closed after accepted events; zero-event receiver can re-arm on healthy new WELCOME | Snapshot/resume not implemented |
| Bad schema/seq/book | Strict receiver/archive validator rejects | All provider variations and attachment initial-book semantics |
| Disk/permission failure | Writer_error; incomplete archive invalid | Final cleanup can skip bridge close; issue #8 |
| Forced kill/power loss | No durability promise; partial GZIP/STOP/summary possible | No repair/fsync/checkpoint durability test |
| Bind conflict | Bridge INVALID/failed, journal independent | Multiple aliases require distinct ports |
| Malformed/untrusted peer | Some commands rejected, parsing failure can invalidate publisher | No authentication; adversarial/parser tests issue #9/#11 |
| UI insufficient height | Measured network-row height, as-needed vertical settings scroll | Native Bookmap 100/125/150% DPI inspection issue #7 |

Counters and health are asynchronous diagnostic snapshots, not a transactionally consistent or durable acknowledgment log. A zero observed gap does not prove completeness after an explicitly signaled overflow. Source callback serialization and safe publication of diagnostics must be confirmed; plain long/HashMap/window counters depend on threading assumptions. Callback histogram quantiles are log2 upper bounds and contain HISTORY/transition time as well as LIVE.

Release acceptance requires failures to remain observable and the independent output not to be silently certified complete. No feature work should bypass these gates merely to display HEALTHY.
