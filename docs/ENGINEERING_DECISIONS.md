> Latest authorized UI: one Orderflow exporter host with Configuration/Live status tabs; Configuration selected first, scrollable contents and fixed action buttons. Earlier two-panel/rollback descriptions below are historical anchors. See [UI follow-up](UI_TAB_FOLLOWUP_2026-10-08.md) for current build, 15 Java tests and native morning acceptance still pending.

# Engineering decisions and tradeoffs

| Decision | Reason recovered from source/history | Cost / alternative |
|---|---|---|
| Bookmap decodes BMF | Existing provider/API exposes MBO and trades; no proprietary BMF decoder here | One-time extraction may follow Bookmap replay constraints. Direct decoding is not implemented. |
| Canonical NDJSON + seq | Readable, portable audit trail; IDs and market clock preserved | JSON/string/decimal costs; efficient compiled research storage belongs downstream. |
| MBO owns book, trades preserved separately | Avoid double-decrementing passive orders already represented by MBO updates | Trade linkage is evidence, not a book mutation instruction. |
| Strict HISTORY journal backpressure | Never silently lose extraction events | Historical callback waits can stall Bookmap until writer capacity/error/interrupt; not a LIVE latency promise. |
| Responsive LIVE journal by default | Favor Bookmap responsiveness; explicitly invalidate archive on overflow | Partial archive cannot be called complete. Opting out restores blocking behavior. |
| Buffer-full writes + soft checkpoints | v0.3→v0.4 replaces payload threshold with observable recorder architecture | 60s checkpoint adds crash-loss exposure; flush never guarantees disk durability. Bookmap's exact private thresholds are undocumented. |
| One immutable callback event, two worker queues | Journal and optional bridge independent; JSON/socket/GZIP off LIVE callback | Suppliers, ID references, queue nodes and cached JSON consume heap; cache may encode twice during race. |
| Single addon, ACKed ROUTER/DEALER + REP health | Preserve one acquisition stream; health clients must not become another market receiver | Single receiver, one port pair/instrument, no auth/TLS/snapshots/durable spool. |
| Retain same-owner unacknowledged events | Socket send success alone is not validated receipt | Bounded retention, explicit overflow INVALID; a new process cannot resume a complete book. Empty receiver recovery is limited to zero accepted events. |
| Historical bridge pacing/drain | Actual catch-up filled the optional 100k buffer before ACK throughput caught up | 10s stalled-wait/drain timeout; transition callback can wait. LIVE offer remains non-waiting. |
| Bounded lifecycle ACK grace | Old sockets must release before settings reload | One-second grace + 1.5s join are not a durable delivery guarantee; exceptional cleanup requires issue #8. |
| Original UI + variable network-row height | FlowLayout wraps; equal GridLayout row allocation clipped lower fields | GridBag equal baseline for other rows, dynamic network preferred/min height and fallback scrolling. Tab/larger-dialog approaches were tried and reverted after owner feedback. |
| Public/private split | Acquisition contracts auditable without revealing decision logic | Private research candidates are not public exporter functionality. |

Not recovered: original quantitative rationale for 1,000,000 writer events, 100,000 bridge events, 256 in-flight window and every UI spacing choice. Current values are verified code facts, not measured universal optimal capacities. The earlier recorder analogy is documented in CHANGELOG, but no stronger Bookmap disk-durability guarantee is inferred.
